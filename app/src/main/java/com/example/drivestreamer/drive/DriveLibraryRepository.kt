package com.example.drivestreamer.drive

import com.example.drivestreamer.auth.TokenProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

data class Album(
    val folderId: String,
    val name: String,
    val tracks: MutableList<Track> = mutableListOf()
)

data class Track(
    val fileId: String,
    val rawFileName: String,
    val albumName: String,
    val displayArtist: String,
    val displayTitle: String
) {
    val displayLabel: String
        get() = if (displayArtist.isNotBlank()) "$displayArtist — $displayTitle" else displayTitle
}

class DriveLibraryRepository {

    companion object {
        private const val TAG_FETCH_CONCURRENCY = 6

        // Save the tag cache to disk this often while scanning, so an
        // interruption loses at most this many tracks' worth of work.
        private const val SAVE_EVERY = 250

        // If this many tracks in a row can't be read at all (after their
        // own retries), Drive or the connection is down. Stop and say so,
        // rather than grinding through thousands more doomed attempts.
        private const val MAX_CONSECUTIVE_FAILURES = 12

        // Compiled once. (It used to be rebuilt on every single comparison
        // while sorting, which added up to seconds of pure waste.)
        private val NUMBER_OR_TEXT = Regex("""\d+|\D+""")
    }

    private val metadataLoader = TrackMetadataLoader()

    private val api: DriveApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(DriveApi.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveApi::class.java)
    }

    suspend fun loadLibrary(
        tokenProvider: TokenProvider,
        rootFolderId: String,
        tagCache: TagCache,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Album> {
        // One token is fine for the folder walk below — even on a huge
        // tree it's metadata-only calls and finishes in seconds, well
        // inside any reasonable token lifetime.
        val bearer = "Bearer ${tokenProvider.getToken()}"

        // Tags already read by an earlier (maybe interrupted) scan.
        tagCache.load()

        // Walk the whole tree first (cheap, metadata-only calls) to find
        // every folder that directly contains audio — at any depth, not
        // just one level below the root — before doing any of the slow
        // per-track tag reads.
        val albumsByFolderId = linkedMapOf<String, Album>()
        val pending = mutableListOf<Pair<String, DriveFile>>() // (albumFolderId, file)
        walkFolder(bearer, rootFolderId, pathSoFar = emptyList(), albumsByFolderId, pending)

        val total = pending.size
        var done = 0
        var consecutiveFailures = 0
        val progressMutex = Mutex()
        val semaphore = Semaphore(TAG_FETCH_CONCURRENCY)

        val results = try {
            coroutineScope {
                pending.map { (folderId, file) ->
                    async {
                        val albumName = albumsByFolderId.getValue(folderId).name

                        // Already read on an earlier scan? Then no network at all.
                        val cached = tagCache.get(file.id, file.modifiedTime)
                        var failed = false
                        val track = if (cached != null) {
                            trackFor(file, albumName, cached.artist, cached.title)
                        } else {
                            val tags = semaphore.withPermit {
                                // Fetched fresh per track rather than reusing
                                // the token grabbed above — on a library this
                                // size the tag-reading phase can run well past
                                // an hour, long enough for that earlier token
                                // to expire mid-run. TokenProvider caches
                                // internally, so this is a cheap no-op call
                                // except when an actual refresh is due.
                                val freshToken = tokenProvider.getToken()
                                metadataLoader.fetchTags(file.id, freshToken)
                            }
                            failed = tags.failed
                            // Remember real answers (including "this file has
                            // no tags"), but not failures: those get another
                            // go on the next scan.
                            if (!failed) tagCache.put(file.id, file.modifiedTime, tags.artist, tags.title)
                            trackFor(file, albumName, tags.artist, tags.title)
                        }

                        progressMutex.withLock {
                            done++
                            if (failed) {
                                consecutiveFailures++
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                    throw IOException(
                                        "Couldn't reach Google Drive. Check your connection " +
                                            "and try again — progress so far is saved."
                                    )
                                }
                            } else {
                                consecutiveFailures = 0
                            }
                            onProgress(done, total)
                            if (done % SAVE_EVERY == 0) tagCache.save()
                        }
                        folderId to track
                    }
                }.awaitAll()
            }
        } finally {
            // Whatever happened — finished, cancelled, or failed — keep
            // what was read. NonCancellable so this still runs when the
            // scan is being cancelled.
            withContext(NonCancellable) { tagCache.save() }
        }

        // Finished: forget cache entries for files that are gone.
        tagCache.retainOnly(pending.map { it.second.id }.toSet())
        tagCache.save()

        results.forEach { (folderId, track) ->
            albumsByFolderId.getValue(folderId).tracks.add(track)
        }

        // Deterministic natural-order sort — "2 - Song" before "10 - Song",
        // not lexicographic "10" before "2" — applied client-side as a
        // guarantee regardless of what order Drive's API actually returned.
        val nameComparator = Comparator<String> { a, b -> naturalCompare(a, b) }
        albumsByFolderId.values.forEach { album ->
            album.tracks.sortWith(compareBy(nameComparator) { it.rawFileName })
        }
        return albumsByFolderId.values
            .filter { it.tracks.isNotEmpty() }
            .sortedWith(compareBy(nameComparator) { it.name })
    }

    /**
     * Recursively walks every folder under [folderId]. A folder that
     * directly contains audio files becomes an "album" — named by its
     * position in the tree (e.g. "Artist / Album" for nested folders,
     * just the folder's own name at the top level) so two same-named
     * album folders under different artists don't collide in the list.
     * Folders are also recursed into regardless of whether they hold
     * audio directly, so arbitrarily deep nesting (root → Artist →
     * Album → tracks, or deeper) is all picked up.
     */
    private suspend fun walkFolder(
        bearer: String,
        folderId: String,
        pathSoFar: List<String>,
        albumsByFolderId: MutableMap<String, Album>,
        pending: MutableList<Pair<String, DriveFile>>
    ) {
        val children = listAllChildren(bearer, folderId)
        val audioFiles = children.filter { it.isAudio }
        val subfolders = children.filter { it.isFolder }

        if (audioFiles.isNotEmpty()) {
            val albumName = if (pathSoFar.isEmpty()) "Loose tracks" else pathSoFar.joinToString(" / ")
            albumsByFolderId[folderId] = Album(folderId, albumName)
            audioFiles.forEach { pending.add(folderId to it) }
        }

        for (folder in subfolders) {
            walkFolder(bearer, folder.id, pathSoFar + folder.name, albumsByFolderId, pending)
        }
    }

    /** Builds a track, using the filename for any tag the file didn't have. */
    private fun trackFor(audioFile: DriveFile, albumName: String, artist: String?, title: String?): Track {
        val (fallbackArtist, fallbackTitle) = TrackNameParser.parse(audioFile.name)
        return Track(
            fileId = audioFile.id,
            rawFileName = audioFile.name,
            albumName = albumName,
            displayArtist = artist ?: fallbackArtist,
            displayTitle = title ?: fallbackTitle
        )
    }

    private suspend fun listAllChildren(bearer: String, folderId: String): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val response = api.listChildren(
                bearerToken = bearer,
                query = DriveApi.queryForChildren(folderId),
                pageToken = pageToken
            )
            result.addAll(response.files)
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return result
    }

    /** "2" sorts before "10"; case-insensitive comparison on non-numeric runs. */
    private fun naturalCompare(a: String, b: String): Int {
        val aParts = NUMBER_OR_TEXT.findAll(a).map { it.value }.toList()
        val bParts = NUMBER_OR_TEXT.findAll(b).map { it.value }.toList()
        val len = minOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val ap = aParts[i]
            val bp = bParts[i]
            val bothNumeric = ap.all { it.isDigit() } && bp.all { it.isDigit() }
            val cmp = if (bothNumeric) {
                // Compare by numeric value without risking overflow on
                // unusually long digit runs: strip leading zeros, compare
                // by length first, then lexicographically as a tiebreak.
                val an = ap.trimStart('0').ifEmpty { "0" }
                val bn = bp.trimStart('0').ifEmpty { "0" }
                if (an.length != bn.length) an.length.compareTo(bn.length) else an.compareTo(bn)
            } else {
                ap.compareTo(bp, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }
}