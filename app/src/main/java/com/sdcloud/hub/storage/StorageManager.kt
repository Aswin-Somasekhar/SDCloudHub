package com.sdcloud.hub.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager as AndroidStorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

data class StorageVolumeInfo(
    val name: String,
    val path: File,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedPercent: Int get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
}

class StorageManager(private val context: Context) {

    private var activeRoot: File = getDefaultRoot()

    fun getActiveRoot(): File = activeRoot

    fun setActiveRoot(file: File) {
        if (file.exists() && file.isDirectory) {
            activeRoot = file
        }
    }

    /**
     * Finds all available storage devices: Primary Internal Storage & Secondary MicroSD Cards
     */
    fun getAvailableStorageVolumes(): List<StorageVolumeInfo> {
        val list = mutableListOf<StorageVolumeInfo>()

        // 1. Primary Internal Storage
        val internalDir = Environment.getExternalStorageDirectory()
        list.add(
            StorageVolumeInfo(
                name = "Internal Storage",
                path = internalDir,
                isRemovable = false,
                totalBytes = internalDir.totalSpace,
                freeBytes = internalDir.freeSpace
            )
        )

        // 2. Discover Removable SD Cards via getExternalFilesDirs
        val externalFilesDirs = ContextCompat.getExternalFilesDirs(context, null)
        for (dir in externalFilesDirs) {
            if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                val fullPath = dir.absolutePath
                val parts = fullPath.split("/Android/data/".toRegex())
                if (parts.isNotEmpty()) {
                    val root = File(parts[0])
                    if (root.exists() && root.canRead()) {
                        val name = "MicroSD Card (${root.name})"
                        // Avoid duplicates
                        if (list.none { it.path.absolutePath == root.absolutePath }) {
                            list.add(
                                StorageVolumeInfo(
                                    name = name,
                                    path = root,
                                    isRemovable = true,
                                    totalBytes = root.totalSpace,
                                    freeBytes = root.freeSpace
                                )
                            )
                        }
                    }
                }
            }
        }

        return list
    }

    private fun getDefaultRoot(): File {
        val volumes = getAvailableStorageVolumes()
        // Prefer removable SD card if available and writable
        val sdCard = volumes.firstOrNull { it.isRemovable && it.path.canWrite() }
        return sdCard?.path ?: Environment.getExternalStorageDirectory()
    }

    /**
     * Resolves a relative path to a File object, ensuring security against directory traversal.
     */
    fun resolve(relativePath: String): File? {
        val sanitized = relativePath.trimStart('/', '\\')
        val target = File(activeRoot, sanitized).canonicalFile
        val rootCanonical = activeRoot.canonicalFile

        // Path traversal protection
        return if (target.absolutePath.startsWith(rootCanonical.absolutePath)) {
            target
        } else {
            null
        }
    }

    /**
     * Lists children of a directory as MediaItems
     */
    fun listItems(directory: File): List<MediaItem> {
        if (!directory.exists() || !directory.isDirectory) return emptyList()

        val files = directory.listFiles() ?: return emptyList()

        // Sort directories first, then alphabetical
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .map { file ->
                val mimeType = resolveMimeType(file)
                val relativePath = file.relativeTo(activeRoot).path.replace('\\', '/')

                MediaItem(
                    id = relativePath.ifEmpty { "0" },
                    name = file.name,
                    path = relativePath,
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified(),
                    mimeType = mimeType
                )
            }
    }

    fun resolveMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"
        val ext = file.extension.lowercase(Locale.ROOT)

        // Custom mappings for common media formats often missing in Android MimeTypeMap
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "srt" -> "text/plain"
            "sub" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /**
     * Extracts video duration in milliseconds (best-effort)
     */
    fun extractVideoDuration(file: File): Long {
        if (!file.exists() || file.isDirectory) return 0L
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
