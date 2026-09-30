package com.opposhot.link.core

import android.content.Context
import com.opposhot.link.net.FileBegin
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 接收端：把分片拼成一个临时文件，收完校验整文件 SHA-256，然后落进相册。
 *
 * 说明：分片靠 TCP 保证「有序、不丢、不重」，所以这里不做逐片 ACK；
 * 但仍然检查分片序号是否连续 —— 发送端出错时能立刻发现，而不是等到最后校验失败。
 *
 * 同一时刻只处理一个传输（`current`）。所以每帧都带 peerId 校验。
 * 会话线程、保存线程、主线程（用户取消）都会碰 `current`，所以公开方法都加锁。
 */
class FileReceiver(
    private val context: Context,
    private val sendDone: (peerId: String, id: String, ok: Boolean, note: String) -> Unit,
    private val onProgress: (id: String, percent: Int) -> Unit,
    private val onSaved: (id: String, path: String) -> Unit,
    private val onFailed: (id: String, reason: String) -> Unit,
) {

    private companion object {
        const val TAG = "FileReceiver"
        const val MAX_ACCEPT_BYTES = 2L * 1024 * 1024 * 1024 // 单文件上限 2GB
        /** 超过这个大小才显示进度（否则一闪而过的 0% 很烦）。 */
        const val PROGRESS_MIN_BYTES = 2L * 1024 * 1024
        const val TEMP_PREFIX = "recv_"
        const val TEMP_SUFFIX = ".tmp"
        /** 比这更旧的临时文件一定是上次进程被杀留下的。 */
        const val STALE_TEMP_MS = 10 * 60 * 1000L
    }

    private var current: Transfer? = null

    private class Transfer(
        val peerId: String,
        val begin: FileBegin,
        val file: File,
        val output: FileOutputStream,
        val digest: MessageDigest,
    ) {
        var written = 0L
        var lastPercent = -1
        var nextIndex = 0
    }

    init {
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles()
            ?.filter {
                it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(TEMP_SUFFIX) &&
                    now - it.lastModified() > STALE_TEMP_MS
            }
            ?.forEach { stale ->
                if (stale.delete()) AppLog.info(TAG, "清理上次残留的临时文件：${stale.name}")
            }
    }

    @Synchronized
    fun onBegin(begin: FileBegin, peerId: String) {
        current?.let { fail(it, "传输被新的文件打断") }
        if (begin.size > MAX_ACCEPT_BYTES) {
            AppLog.error(TAG, "文件太大，拒绝接收：${begin.size} 字节")
            sendDone(peerId, begin.id, false, "文件太大")
            return
        }
        val temp = File(context.cacheDir, "$TEMP_PREFIX${System.nanoTime()}$TEMP_SUFFIX")
        current = Transfer(
            peerId = peerId,
            begin = begin,
            file = temp,
            output = FileOutputStream(temp),
            digest = MessageDigest.getInstance("SHA-256"),
        )
        AppLog.event(TAG, "开始接收 ${begin.name}（${begin.size} 字节，分片 ${begin.chunkSize}）")
        onProgress(begin.id, 0)
    }

    @Synchronized
    fun onChunk(peerId: String, id: String, index: Int, data: ByteArray) {
        val transfer = current
        if (transfer == null || transfer.begin.id != id || transfer.peerId != peerId) {
            AppLog.error(TAG, "收到无主分片（来自 $peerId，id=$id index=$index），丢弃")
            return
        }
        if (index != transfer.nextIndex) {
            fail(transfer, "分片乱序（收到第 $index 片，应为第 ${transfer.nextIndex} 片）")
            return
        }
        try {
            transfer.output.write(data)
            transfer.digest.update(data)
            transfer.written += data.size
            transfer.nextIndex++
        } catch (e: Exception) {
            AppLog.error(TAG, "写临时文件失败: ${e.message}")
            fail(transfer, "写文件失败")
            return
        }

        val total = transfer.begin.size
        // 只有大文件才刷进度（小文件毫秒级传完，刷进度只会留下一闪而过的 0%）
        if (total > PROGRESS_MIN_BYTES) {
            val percent = ((transfer.written * 100) / total).toInt()
            if (percent != transfer.lastPercent) {
                transfer.lastPercent = percent
                onProgress(id, percent)
            }
        }
    }

    fun onEnd(peerId: String, id: String) {
        val transfer = synchronized(this) {
            val t = current
            if (t == null || t.begin.id != id || t.peerId != peerId) {
                AppLog.error(TAG, "收到无主的 FILE_END（id=$id）")
                return
            }
            current = null
            t
        }
        try {
            transfer.output.flush()
            transfer.output.close()
        } catch (e: Exception) {
            AppLog.error(TAG, "关闭临时文件失败: ${e.message}")
        }
        // 大视频复制进相册要好几秒；放在会话线程里做会让连接长时间不读数据，可能被判超时断开。
        Thread({ verifyAndSave(transfer) }, "opposhot-save").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun verifyAndSave(transfer: Transfer) {
        val id = transfer.begin.id
        val actualSize = transfer.file.length()
        val actualSha = transfer.digest.digest().joinToString("") { "%02x".format(it) }
        val expected = transfer.begin.sha256

        if (transfer.begin.size > 0 && actualSize != transfer.begin.size) {
            fail(transfer, "大小不符（收到 $actualSize，应为 ${transfer.begin.size}）")
            return
        }
        if (expected.isNotEmpty() && actualSha != expected) {
            fail(transfer, "校验失败（SHA-256 不一致）")
            return
        }

        val path = ReceivedFileStore.save(context, transfer.begin.name, transfer.begin.mime, transfer.file)
        runCatching { transfer.file.delete() }
        if (path == null) {
            fail(transfer, "写入相册失败")
            return
        }

        AppLog.event(TAG, "接收完成并已保存：$path")
        onSaved(id, path)
        sendDone(transfer.peerId, id, true, path)
    }

    /** 发文件的那台设备断开了：丢掉半截文件，并告诉用户（对端已经不在，不用回 FILE_DONE）。 */
    @Synchronized
    fun abortFrom(peerId: String) {
        val transfer = current ?: return
        if (transfer.peerId != peerId) return
        fail(transfer, "连接断开，可以在记录里重新保存", tellPeer = false)
    }

    private fun fail(transfer: Transfer, reason: String, tellPeer: Boolean = true) {
        AppLog.error(TAG, "接收失败：$reason")
        drop(transfer)
        onFailed(transfer.begin.id, reason)
        if (tellPeer) sendDone(transfer.peerId, transfer.begin.id, false, reason)
    }

    private fun drop(transfer: Transfer) {
        synchronized(this) { if (current === transfer) current = null }
        runCatching { transfer.output.close() }
        runCatching { transfer.file.delete() }
    }

    /** 当前正在接收的 id（没有则 null）。 */
    @Synchronized
    fun activeId(): String? = current?.begin?.id

    /** 用户取消：丢掉临时文件。 */
    @Synchronized
    fun cancel(id: String): Boolean {
        val transfer = current ?: return false
        if (transfer.begin.id != id) return false
        drop(transfer)
        AppLog.event(TAG, "已取消接收（$id）")
        return true
    }
}
