package com.sdcloud.hub.storage

data class MediaItem(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    val isImage: Boolean get() = mimeType.startsWith("image/")

    val upnpClass: String get() = when {
        isDirectory -> "object.container.storageFolder"
        isVideo -> "object.item.videoItem.movie"
        isAudio -> "object.item.audioItem.musicTrack"
        isImage -> "object.item.imageItem.photo"
        else -> "object.item"
    }
}
