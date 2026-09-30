package com.opposhot.link.net

import java.io.EOFException
import java.io.InputStream

/**
 * 帧协议（与技术方案第 14.2 节一致）：
 *
 *   [4B magic "OSS1"][1B type][4B payloadLen (big-endian)][payload]
 *
 * 文本消息的 payload 是 UTF-8；缩略图这种二进制用 ByteArray 版本。
 */
object Proto {

    val MAGIC = byteArrayOf('O'.code.toByte(), 'S'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val HEADER_SIZE = 9
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    const val TYPE_HELLO = 1
    const val TYPE_HELLO_ACK = 2
    const val TYPE_PING = 3
    const val TYPE_PONG = 4
    // 5 曾是 TEXT（Phase 1 的测试消息），已删除；编号保留不再复用。
    const val TYPE_MEDIA_EVENT = 6
    const val TYPE_MEDIA_THUMB = 7
    const val TYPE_ACCEPT = 8
    const val TYPE_DECLINE = 9
    const val TYPE_FILE_BEGIN = 10
    const val TYPE_CHUNK = 11
    const val TYPE_FILE_END = 12
    const val TYPE_FILE_DONE = 13
    const val TYPE_CANCEL = 14

    /**
     * 剪贴板：功能已移除。
     * 这里**故意保留常量**，只为协议向前兼容 —— 老版本发来的 CLIP 帧
     * 我们能认出帧类型并安静忽略，而不是当成未知帧报错。
     */
    const val TYPE_CLIP = 15

    /** 握手报文里带的固定文本，方便日志里一眼认出。 */
    const val HELLO_TEXT = "HELLO_FROM_OPPOSHOT"

    fun typeName(type: Int): String = when (type) {
        TYPE_HELLO -> "HELLO"
        TYPE_HELLO_ACK -> "HELLO_ACK"
        TYPE_PING -> "PING"
        TYPE_PONG -> "PONG"
        TYPE_MEDIA_EVENT -> "MEDIA_EVENT"
        TYPE_MEDIA_THUMB -> "MEDIA_THUMB"
        TYPE_ACCEPT -> "ACCEPT"
        TYPE_DECLINE -> "DECLINE"
        TYPE_FILE_BEGIN -> "FILE_BEGIN"
        TYPE_CHUNK -> "CHUNK"
        TYPE_FILE_END -> "FILE_END"
        TYPE_FILE_DONE -> "FILE_DONE"
        TYPE_CANCEL -> "CANCEL"
        TYPE_CLIP -> "CLIP"
        else -> "UNKNOWN($type)"
    }

    /** 这些帧不逐条记日志。 */
    fun isHighFrequency(type: Int): Boolean =
        type == TYPE_CHUNK || type == TYPE_PING || type == TYPE_PONG

    class Frame(val type: Int, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    fun encode(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        MAGIC.copyInto(out, 0)
        out[4] = type.toByte()
        out[5] = (payload.size ushr 24 and 0xFF).toByte()
        out[6] = (payload.size ushr 16 and 0xFF).toByte()
        out[7] = (payload.size ushr 8 and 0xFF).toByte()
        out[8] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** 阻塞读取一帧；流结束返回 null。 */
    fun read(input: InputStream): Frame? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) return null
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return null
        }
        val type = header[4].toInt() and 0xFF
        val length = (header[5].toInt() and 0xFF shl 24) or
            (header[6].toInt() and 0xFF shl 16) or
            (header[7].toInt() and 0xFF shl 8) or
            (header[8].toInt() and 0xFF)
        if (length < 0 || length > MAX_PAYLOAD) throw IllegalStateException("非法帧长度: $length")
        val body = ByteArray(length)
        if (length > 0 && !readFully(input, body)) return null
        return Frame(type, body)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw EOFException("帧被截断")
            }
            offset += read
        }
        return true
    }
}
