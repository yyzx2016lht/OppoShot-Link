package com.opposhot.link.core

import android.content.Context
import com.opposhot.link.net.MediaItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「最近收到」：按设备分组，持久化保存最近 50 条。
 *
 * 通知 2 分钟就消失了，如果用户没点，那条就永久找不到了；这里兜底。
 * 存法：缩略图写 App 私有目录，元数据每行一条写进 received.txt。
 */
object ReceivedStore {

    private const val TAG = "ReceivedStore"
    private const val MAX_ITEMS = 50
    private const val FILE_NAME = "received.txt"
    private const val THUMB_DIR = "received_thumbs"

    data class Entry(
        val id: String,
        val deviceId: String,
        val deviceName: String,
        val kind: String,
        val name: String,
        val sizeBytes: Long,
        val receivedAt: Long,
        val thumbFile: String?,
        var saved: Boolean,
    ) {
        val isVideo: Boolean get() = kind == MediaItem.KIND_VIDEO

        fun timeText(): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(receivedAt))
    }

    /** 内存里的副本：界面每次刷新都会来读，不能每次都去读盘。 */
    private var cache: List<Entry>? = null

    @Synchronized
    fun add(context: Context, fromDeviceId: String, fromName: String, item: MediaItem, thumb: ByteArray?) {
        val entries = load(context).toMutableList()
        entries.removeAll { it.id == item.id }

        val thumbName = if (thumb != null) {
            val f = File(thumbDir(context), "${item.id}.jpg")
            runCatching { f.writeBytes(thumb) }
            if (f.exists()) f.name else null
        } else {
            null
        }

        entries.add(
            0,
            Entry(
                id = item.id,
                deviceId = fromDeviceId,
                deviceName = fromName.ifBlank { "未知设备" },
                kind = item.kind,
                name = item.displayName,
                sizeBytes = item.sizeBytes,
                receivedAt = System.currentTimeMillis(),
                thumbFile = thumbName,
                saved = false,
            )
        )
        save(context, entries.take(MAX_ITEMS), entries.drop(MAX_ITEMS))
    }

    @Synchronized
    fun markSaved(context: Context, id: String) {
        val entries = load(context).toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        entries[index] = entries[index].copy(saved = true)
        save(context, entries, emptyList())
    }

    @Synchronized
    fun list(context: Context): List<Entry> = load(context)

    /**
     * 删除一条记录（连同它的缩略图文件）。
     * 对方已经把照片删了的时候用：留着这条只会让人反复点一个永远拿不到的东西。
     * 返回是否真的删掉了。
     */
    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val entries = load(context).toMutableList()
        val target = entries.firstOrNull { it.id == id } ?: return false
        entries.remove(target)
        target.thumbFile?.let { name ->
            runCatching { File(thumbDir(context), name).delete() }
        }
        save(context, entries, emptyList())
        AppLog.event("ReceivedStore", "删除失效记录：${target.name}")
        return true
    }

    @Synchronized
    fun clear(context: Context) {
        cache = emptyList()
        runCatching { file(context).delete() }
        runCatching { thumbDir(context).deleteRecursively() }
        AppLog.info(TAG, "已清空「最近收到」")
    }

    fun thumbFile(context: Context, entry: Entry): File? {
        val name = entry.thumbFile ?: return null
        val f = File(thumbDir(context), name)
        return if (f.exists()) f else null
    }

    // ---------------------------------------------------------------- 存取

    private fun save(context: Context, keep: List<Entry>, drop: List<Entry>) {
        cache = keep
        runCatching {
            file(context).writeText(keep.joinToString("\n") { encode(it) })
        }.onFailure { AppLog.error(TAG, "保存列表失败: ${it.message}") }
        drop.forEach { entry ->
            entry.thumbFile?.let { runCatching { File(thumbDir(context), it).delete() } }
        }
    }

    private fun load(context: Context): List<Entry> {
        cache?.let { return it }
        val f = file(context)
        val loaded = if (!f.exists()) {
            emptyList()
        } else {
            runCatching {
                f.readLines().mapNotNull { decode(it) }
            }.getOrElse {
                AppLog.error(TAG, "读取列表失败: ${it.message}")
                emptyList()
            }
        }
        cache = loaded
        return loaded
    }

    private fun encode(e: Entry): String = listOf(
        e.id,
        e.deviceId,
        e.deviceName.replace('|', '_'),
        e.kind,
        e.name.replace('|', '_'),
        e.sizeBytes.toString(),
        e.receivedAt.toString(),
        e.thumbFile ?: "-",
        if (e.saved) "1" else "0",
    ).joinToString("|")

    private fun decode(line: String): Entry? {
        val p = line.split("|")
        if (p.size < 9) return null
        return Entry(
            id = p[0],
            deviceId = p[1],
            deviceName = p[2],
            kind = p[3],
            name = p[4],
            sizeBytes = p[5].toLongOrNull() ?: 0L,
            receivedAt = p[6].toLongOrNull() ?: 0L,
            thumbFile = p[7].takeIf { it != "-" },
            saved = p[8] == "1",
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun thumbDir(context: Context) = File(context.filesDir, THUMB_DIR).apply { mkdirs() }
}
