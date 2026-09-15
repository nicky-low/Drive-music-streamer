package com.example.drivestreamer.drive

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface DriveApi {

    /**
     * Lists children of a folder. Drive API only returns items you have
     * access to, which — since we're signed into the buffer account —
     * is scoped to the shared music folder tree.
     */
    @GET("drive/v3/files")
    suspend fun listChildren(
        @Header("Authorization") bearerToken: String,
        @Query("q") query: String,
        @Query("fields") fields: String = "nextPageToken, files(id, name, mimeType, parents)",
        @Query("pageSize") pageSize: Int = 200,
        @Query("pageToken") pageToken: String? = null
    ): DriveFileListResponse

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"

        fun queryForChildren(folderId: String) =
            "'$folderId' in parents and trashed = false"
    }
}
