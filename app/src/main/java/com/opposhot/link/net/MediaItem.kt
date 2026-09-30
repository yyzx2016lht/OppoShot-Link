package com.opposhot.link.net

/**
 * 一条「媒体事件」的描述。
 *
 * Phase 2 只传元数据 + 小缩略图；Phase 3 用户点通知后才传原图。
 */
data class MediaItem(
    /** 本机唯一 id：image-<MediaStore id> / video-<MediaStore id> */
    val id: String,
    /** image / video */
    val kind: String,
    val displayName: String,
    val sizeBytes: Long,
    val mime: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val relativePath: String,
    val contentUri: String,
    /** 是不是截图（决定它受「截图」开关管，而不是「图片」开关）。 */
    val isScreenshot: Boolean = false,
) {

    val isVideo: Boolean get() = kind == KIND_VIDEO

    /** 内容类型，用于按开关过滤：image / video / screenshot。 */
    val contentKind: String
        get() = when {
            isVideo -> "video"
            isScreenshot -> "screenshot"
            else -> "image"
        }

    fun payload(): String = buildString {
        append("id=").append(id)
        append(";kind=").append(kind)
        append(";name=").append(displayName.sanitize())
        append(";size=").append(sizeBytes)
        append(";mime=").append(mime.sanitize())
        append(";ms=").append(durationMs)
        append(";added=").append(dateAddedSec)
        append(";shot=").append(if (isScreenshot) 1 else 0)
        append(";path=").append(relativePath.sanitize())
    }

    fun summary(): String {
        val sizeText = when {
            sizeBytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(sizeBytes / 1024.0 / 1024 / 1024)
            sizeBytes >= 1024L * 1024 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024)
            sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
        val kindText = if (isVideo) "视频" else "图片"
        val durationText = if (isVideo && durationMs > 0) "，时长 ${durationMs / 1000}s" else ""
        return "$kindText「$displayName」$sizeText$durationText"
    }

    private fun String.sanitize(): String = replace(';', '_').replace('=', '_')

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"

        fun fromPayload(raw: String): MediaItem? {
            val map = HashMap<String, String>()
            raw.split(";").forEach { field ->
                val index = field.indexOf('=')
                if (index <= 0) return@forEach
                map[field.substring(0, index)] = field.substring(index + 1)
            }
            val id = map["id"] ?: return null
            return MediaItem(
                id = id,
                kind = map["kind"] ?: KIND_IMAGE,
                displayName = map["name"] ?: "未知文件",
                sizeBytes = map["size"]?.toLongOrNull() ?: 0L,
                mime = map["mime"] ?: "",
                durationMs = map["ms"]?.toLongOrNull() ?: 0L,
                dateAddedSec = map["added"]?.toLongOrNull() ?: 0L,
                relativePath = map["path"] ?: "",
                contentUri = "",
                isScreenshot = map["shot"] == "1",
            )
        }
    }
}

/** 缩略图帧：[2B 固定 64 的 idLen][64B id][jpeg 数据] */
object ThumbFrame {

    private const val ID_MAX = 64

    fun encode(id: String, jpeg: ByteArray): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8).copyOf(ID_MAX)
        val out = ByteArray(2 + ID_MAX + jpeg.size)
        out[0] = (ID_MAX ushr 8 and 0xFF).toByte()
        out[1] = (ID_MAX and 0xFF).toByte()
        idBytes.copyInto(out, 2)
        jpeg.copyInto(out, 2 + ID_MAX)
        return out
    }

    fun decode(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 2) return null
        val idLen = (payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)
        if (idLen <= 0 || payload.size < 2 + idLen) return null
        val id = String(payload, 2, idLen, Charsets.UTF_8).trim { it <= ' ' }
        val image = payload.copyOfRange(2 + idLen, payload.size)
        return id to image
    }
}

/** 分片帧：[2B 固定 64 的 idLen][64B id][4B index][数据] */
object ChunkFrame {

    private const val ID_MAX = 64

    fun encode(id: String, index: Int, data: ByteArray): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8).copyOf(ID_MAX)
        val out = ByteArray(2 + ID_MAX + 4 + data.size)
        out[0] = (ID_MAX ushr 8 and 0xFF).toByte()
        out[1] = (ID_MAX and 0xFF).toByte()
        idBytes.copyInto(out, 2)
        out[2 + ID_MAX] = (index ushr 24 and 0xFF).toByte()
        out[3 + ID_MAX] = (index ushr 16 and 0xFF).toByte()
        out[4 + ID_MAX] = (index ushr 8 and 0xFF).toByte()
        out[5 + ID_MAX] = (index and 0xFF).toByte()
        data.copyInto(out, 2 + ID_MAX + 4)
        return out
    }

    fun decode(payload: ByteArray): Triple<String, Int, ByteArray>? {
        if (payload.size < 2 + 4) return null
        val idLen = (payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)
        if (idLen <= 0 || payload.size < 2 + idLen + 4) return null
        val id = String(payload, 2, idLen, Charsets.UTF_8).trim { it <= ' ' }
        val index = (payload[2 + idLen].toInt() and 0xFF shl 24) or
            (payload[3 + idLen].toInt() and 0xFF shl 16) or
            (payload[4 + idLen].toInt() and 0xFF shl 8) or
            (payload[5 + idLen].toInt() and 0xFF)
        val data = payload.copyOfRange(2 + idLen + 4, payload.size)
        return Triple(id, index, data)
    }
}

/** FILE_BEGIN 的负载：告诉对端我要发什么、多大、用什么分片、整文件 sha256 是多少。 */
data class FileBegin(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val chunkSize: Int,
    val sha256: String,
) {
    fun payload(): String = buildString {
        append("id=").append(id)
        append(";name=").append(name.replace(';', '_').replace('=', '_'))
        append(";mime=").append(mime.replace(';', '_').replace('=', '_'))
        append(";size=").append(size)
        append(";chunk=").append(chunkSize)
        append(";sha=").append(sha256)
    }

    companion object {
        fun from(raw: String): FileBegin? {
            val map = HashMap<String, String>()
            raw.split(";").forEach { field ->
                val i = field.indexOf('=')
                if (i > 0) map[field.substring(0, i)] = field.substring(i + 1)
            }
            val id = map["id"] ?: return null
            return FileBegin(
                id = id,
                name = map["name"] ?: "received.bin",
                mime = map["mime"] ?: "application/octet-stream",
                size = map["size"]?.toLongOrNull() ?: 0L,
                chunkSize = map["chunk"]?.toIntOrNull() ?: 0,
                sha256 = map["sha"] ?: "",
            )
        }
    }
}

/** FILE_DONE 的负载。 */
data class FileDone(
    val id: String,
    val ok: Boolean,
    val note: String,
    /**
     * 机器可读的错误分类，接收端靠它决定要不要删掉这条历史记录。
     * 不要用中文文案判断 —— 文案一改就失效（已经踩过一次）。
     */
    val reason: String = "",
) {
    fun payload(): String =
        "id=$id;ok=$ok;reason=$reason;note=${note.replace(';', '_').replace('=', '_')}"

    companion object {
        /** 对方相册里已经没有这张了。 */
        const val REASON_GONE = "gone"

        /** 记录还在，但打不开（常见于「部分照片授权」，或文件损坏）。 */
        const val REASON_UNREADABLE = "unreadable"

        fun from(raw: String): FileDone {
            val map = HashMap<String, String>()
            raw.split(";").forEach { field ->
                val i = field.indexOf('=')
                if (i > 0) map[field.substring(0, i)] = field.substring(i + 1)
            }
            return FileDone(
                id = map["id"] ?: "",
                ok = map["ok"] == "true",
                note = map["note"] ?: "",
                reason = map["reason"] ?: "",
            )
        }
    }
}
