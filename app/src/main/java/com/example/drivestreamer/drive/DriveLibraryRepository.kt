package com.example.drivestreamer.drive

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
    /** "Artist — Title" if we found an artist, otherwise just the title. */
    val displayLabel: String
        get() = if (displayArtist.isNotBlank()) "$displayArtist — $displayTitle" else displayTitle
}

class DriveLibraryRepository {

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

    suspend fun loadLibrary(accessToken: String, rootFolderId: String): List<Album> {
        val bearer = "Bearer $accessToken"
        val albums = mutableListOf<Album>()

        val topLevel = listAllChildren(bearer, rootFolderId)

        val albumFolders = topLevel.filter { it.isFolder }
        for (folder in albumFolders) {
            val album = Album(folderId = folder.id, name = folder.name)
            val children = listAllChildren(bearer, folder.id)
            children.filter { it.isAudio }.forEach { audioFile ->
                album.tracks.add(buildTrack(audioFile, album.name))
            }
            if (album.tracks.isNotEmpty()) albums.add(album)
        }

        val rootAudio = topLevel.filter { it.isAudio }
        if (rootAudio.isNotEmpty()) {
            val loose = Album(folderId = rootFolderId, name = "Loose tracks")
            rootAudio.forEach { loose.tracks.add(buildTrack(it, loose.name)) }
            albums.add(loose)
        }

        return albums
    }

    private fun buildTrack(audioFile: DriveFile, albumName: String): Track {
        val (artist, title) = TrackNameParser.parse(audioFile.name)
        return Track(
            fileId = audioFile.id,
            rawFileName = audioFile.name,
            albumName = albumName,
            displayArtist = artist,
            displayTitle = title
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