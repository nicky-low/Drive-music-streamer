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
    /** "Artist — Title" if we have an artist, otherwise just the title. */
    val displayLabel: String
        get() = if (displayArtist.isNotBlank()) "$displayArtist — $displayTitle" else displayTitle
}

class DriveLibraryRepository {

    companion object {
        // How many files we read tags from at once. High enough to be
        // fast on a big library, low enough not to look like abuse to
        // Drive's API or saturate a slow connection.
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

    /**
     * onProgress reports (tracksTaggedSoFar, totalTracks) as ID3 reads
     * complete, so the caller can show a live "Loading 42 / 210" message —
     * reading tags means an extra network round-trip per track, so this
     * step is the slow part on a big library, not the folder listing.
     */
    suspend fun loadLibrary(
        accessToken: String,
        rootFolderId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Album> {
        val bearer = "Bearer $accessToken"
        val topLevel = listAllChildren(bearer, rootFolderId)
        val albumFolders = topLevel.filter { it.isFolder }

        // First, work out the folder structure and which files need
        // tagging — cheap, metadata-only calls, no per-file network yet.
        val albumsByFolderId = linkedMapOf<String, Album>()
        val pending = mutableListOf<Pair<String, DriveFile>>() // (albumFolderId, file)

        for (folder in albumFolders) {
            val children = listAllChildren(bearer, folder.id)
            val audioFiles = children.filter { it.isAudio }
            if (audioFiles.isNotEmpty()) {
                albumsByFolderId[folder.id] = Album(folder.id, folder.name)
                audioFiles.forEach { pending.add(folder.id to it) }
            }
        }

        val rootAudio = topLevel.filter { it.isAudio }
        if (rootAudio.isNotEmpty()) {
            albumsByFolderId[rootFolderId] = Album(rootFolderId, "Loose tracks")
            rootAudio.forEach { pending.add(rootFolderId to it) }
        }

        // Now the slow part: read ID3/etc tags per file, several at once
        // but capped, reporting progress as each one finishes.
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

        // awaitAll preserves submission order, so tracks land back in
        // their original Drive listing order within each album.
        results.forEach { (folderId, track) ->
            albumsByFolderId.getValue(folderId).tracks.add(track)
        }

        return albumsByFolderId.values.filter { it.tracks.isNotEmpty() }
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
}