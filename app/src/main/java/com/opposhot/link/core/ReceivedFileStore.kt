package com.opposhot.link.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 把收到的文件写进系统相册，放在专用目录里。
 *
 * 目录名 MediaWatcher.RECEIVED_DIR（OppoShotReceived）会被监听逻辑排除，
 * 所以「收到 → 保存 → 又被当成新文件发回去」这个死循环不会发生。
 */
object ReceivedFileStore {

    private const val TAG = "ReceivedFileStore"

    /** 返回写进相册后的相对路径，失败返回 null。 */
    fun save(context: Context, name: String, mime: String, source: File): String? {
        val isVideo = mime.startsWith("video")
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val relativeDir = if (isVideo) {
            "Movies/${MediaWatcher.RECEIVED_DIR}"
        } else {
            "Pictures/${MediaWatcher.RECEIVED_DIR}"
        }
        val safeName = when {
            name.contains('.') -> name
            isVideo -> "$name.mp4"
            else -> "$name.jpg"
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, "/sdcard/$relativeDir/$safeName")
            }
        }

        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (uri == null) {
            AppLog.error(TAG, "写入相册失败：insert 返回空")
            return null
        }

        return try {
            var copied = 0L
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
            }
            AppLog.event(TAG, "已保存到相册：$relativeDir/$safeName（$copied 字节）")
            "$relativeDir/$safeName"
        } catch (e: Exception) {
            AppLog.error(TAG, "写入相册失败: ${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
