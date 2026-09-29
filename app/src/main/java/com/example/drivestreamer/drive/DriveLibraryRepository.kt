package com.example.drivestreamer.drive

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
    }

    private val metadataLoader = TrackMetadataLoader()

    private val api: DriveApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(DriveApi.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DriveApi::class.java)
    }

    suspend fun loadLibrary(
        accessToken: String,
        rootFolderId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Album> {
        val bearer = "Bearer $accessToken"

        // Walk the whole tree first (cheap, metadata-only calls) to find
        // every folder that directly contains audio — at any depth, not
        // just one level below the root — before doing any of the slow
        // per-track tag reads.
        val albumsByFolderId = linkedMapOf<String, Album>()
        val pending = mutableListOf<Pair<String, DriveFile>>() // (albumFolderId, file)
        walkFolder(bearer, rootFolderId, pathSoFar = emptyList(), albumsByFolderId, pending)

        val total = pending.size
        var done = 0
        val progressMutex = Mutex()
        val semaphore = Semaphore(TAG_FETCH_CONCURRENCY)

        val results = coroutineScope {
            pending.map { (folderId, file) ->
                async {
                    val track = semaphore.withPermit {
                        buildTrack(file, albumsByFolderId.getValue(folderId).name, accessToken)
                    }
                    progressMutex.withLock {
                        done++
                        onProgress(done, total)
                    }
                    folderId to track
                }
            }.awaitAll()
        }

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

    private suspend fun buildTrack(audioFile: DriveFile, albumName: String, accessToken: String): Track {
        val tags = metadataLoader.fetchTags(audioFile.id, accessToken)
        val (fallbackArtist, fallbackTitle) = TrackNameParser.parse(audioFile.name)
        return Track(
            fileId = audioFile.id,
            rawFileName = audioFile.name,
            albumName = albumName,
            displayArtist = tags.artist ?: fallbackArtist,
            displayTitle = tags.title ?: fallbackTitle
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
        val regex = Regex("""\d+|\D+""")
        val aParts = regex.findAll(a).map { it.value }.toList()
        val bParts = regex.findAll(b).map { it.value }.toList()
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
