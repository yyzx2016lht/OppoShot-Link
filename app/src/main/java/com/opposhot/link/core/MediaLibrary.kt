package com.opposhot.link.core

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.opposhot.link.net.MediaItem
import java.io.InputStream
import java.security.MessageDigest

/**
 * 按 id（`image-123` / `video-123`）回查本机相册里的文件。
 *
 * 这样即使 App 被重启（内存里的候选列表没了），只要文件还在相册里就能发出去。
 */
object MediaLibrary {

    private const val TAG = "MediaLibrary"

    private fun collection(kind: String): Uri =
        if (kind == MediaItem.KIND_VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun parse(id: String): Pair<String, Long>? {
        val index = id.lastIndexOf('-')
        if (index <= 0) return null
        val kind = id.substring(0, index)
        val mediaId = id.substring(index + 1).toLongOrNull() ?: return null
        if (kind != MediaItem.KIND_IMAGE && kind != MediaItem.KIND_VIDEO) return null
        return kind to mediaId
    }

    fun uriOf(id: String): Uri? {
        val (kind, mediaId) = parse(id) ?: return null
        return ContentUris.withAppendedId(collection(kind), mediaId)
    }

    /** 读回文件的基本信息（大小/类型/名字）。 */
    fun info(context: Context, id: String): Info? {
        val uri = uriOf(id) ?: return null
        val (kind, _) = parse(id) ?: return null
        var name = "received_$id"
        var mime = if (kind == MediaItem.KIND_VIDEO) "video/mp4" else "image/jpeg"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                ),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    mime = cursor.getString(1) ?: mime
                    size = cursor.getLong(2)
                }
            }
        }.onFailure {
            AppLog.error(TAG, "读取文件信息失败（$id）: ${it.javaClass.simpleName} ${it.message}")
            return null
        }
        return Info(uri, name, mime, size, kind)
    }

    fun openStream(context: Context, id: String): InputStream? {
        val uri = uriOf(id) ?: return null
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /** 流式算整文件 SHA-256（21 MB 视频大概几百毫秒）。 */
    fun sha256(context: Context, id: String): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            openStream(context, id)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLog.error(TAG, "计算 sha256 失败（$id）: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    data class Info(val uri: Uri, val name: String, val mime: String, val size: Long, val kind: String)
}
