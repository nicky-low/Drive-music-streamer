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
    val title: String,
    val albumName: String
)

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

    /**
     * Walks the given root folder one level for albums (subfolders),
     * then walks each album folder for audio tracks.
     *
     * rootFolderId = the ID of your shared music folder (or the shortcut's
     * target ID) in the BUFFER account's Drive.
     */
    suspend fun loadLibrary(accessToken: String, rootFolderId: String): List<Album> {
        val bearer = "Bearer $accessToken"
        val albums = mutableListOf<Album>()

        val topLevel = listAllChildren(bearer, rootFolderId)

        // Folders directly under root are treated as albums.
        val albumFolders = topLevel.filter { it.isFolder }
        for (folder in albumFolders) {
            val album = Album(folderId = folder.id, name = folder.name)
            val children = listAllChildren(bearer, folder.id)
            children.filter { it.isAudio }.forEach { audioFile ->
                album.tracks.add(Track(audioFile.id, audioFile.name, album.name))
            }
            if (album.tracks.isNotEmpty()) albums.add(album)
        }

        // Also handle audio files sitting directly in the root (no album folder).
        val rootAudio = topLevel.filter { it.isAudio }
        if (rootAudio.isNotEmpty()) {
            val loose = Album(folderId = rootFolderId, name = "Loose tracks")
            rootAudio.forEach { loose.tracks.add(Track(it.id, it.name, loose.name)) }
            albums.add(loose)
        }

        return albums
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
