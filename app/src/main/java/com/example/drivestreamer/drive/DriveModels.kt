package com.example.drivestreamer.drive

data class DriveFileListResponse(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null
)

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String>? = null,
    // Used to tell whether a file has changed since its tags were last read.
    val modifiedTime: String? = null
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
}