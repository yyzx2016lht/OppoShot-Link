package com.opposhot.link.core

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import com.opposhot.link.net.MediaItem
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 监听「本机新保存进来的图片 / 视频」。
 *
 * 做法：
 *  - 定时轮询 MediaStore（ColorOS 上 ContentObserver 不可靠，只当作「提前扫一次」的提示）；
 *  - 用 `_id > 水位线` 增量查询（`date_added` 实测会查到 0 条）；
 *  - 生成一个 ≤ 64KB 的小缩略图，连同元数据一起推给对端（原图不传）。
 *
 * 必须注意的点：
 *  1. **防循环**：「接收到的文件」写回相册的目录必须排除，
 *     否则 A 发给 B、B 保存后又变成新文件发回 A，会无限来回。
 *  2. **权限**：要看到别的 App 保存的媒体，必须 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO。
 *  3. **还没写完的文件**：相机写入期间行是 IS_PENDING=1，默认查不到。
 *     如果它后面的行先写完，水位线会越过它 —— 所以每次都往回多看 [LOOKBACK_IDS] 个 id，
 *     再用「已处理过的 id」去重。
 */
class MediaWatcher(
    private val context: Context,
    private val onlyScreenshots: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val onNewMedia: (MediaItem, ByteArray?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onVisibility: (Int) -> Unit,
) {

    companion object {
        private const val TAG = "MediaWatcher"
        private const val SCAN_DEBOUNCE_MS = 700L
        private const val POLL_INTERVAL_MS = 5_000L
        private const val POLL_IDLE_MS = 15_000L
        private const val THUMB_MAX_DIM = 512
        private const val THUMB_MAX_BYTES = 60 * 1024
        /** _id 是整个媒体库共用的自增序号（别的文件也会占号），所以要多留一些。 */
        private const val LOOKBACK_IDS = 200L
        private const val MAX_REPORTED = 500

        /** 收到对端文件后保存到这个目录：必须排除，否则会无限来回。 */
        const val RECEIVED_DIR = "OppoShotReceived"
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null

    /** 每个集合的增量状态。只在 HandlerThread 上读写。 */
    private class Track {
        /** 开始监听那一刻的最大 _id，只处理比它新的；-1 = 还没拿到（没权限时拿到的不算数）。 */
        var baseline = -1L
        var watermark = 0L
        val reported = LinkedHashSet<Long>()
    }

    private val images = Track()
    private val videos = Track()
    private var scanPending = false
    private var seenCount = 0

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        val worker = HandlerThread("opposhot-media").also { it.start() }
        thread = worker
        val h = Handler(worker.looper)
        handler = h

        imageObserver = observer()
        videoObserver = observer()

        imageObserver?.let {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it
            )
        }
        videoObserver?.let {
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it
            )
        }

        startPolling()
        h.post {
            val visible = probeVisibility()
            seed(images, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            seed(videos, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            AppLog.event(TAG, "已开始监听相册：从现在起新保存的文件会通知对端")
            AppLog.event(
                TAG,
                "媒体可见性自检：能看到 $visible 张图片；当前最大 id：图片=${images.baseline} 视频=${videos.baseline}"
            )
            onVisibility(visible)
            onStatus("监听中（相册里能看到 $visible 张图片）")
        }
    }

    /**
     * 记下「从哪开始算新文件」。没权限时查到的最大 id 是 0（或只有本 App 自己的文件），
     * 那时候记下来，等用户授权后第一次扫描就会把整个相册当成新文件推给对端。
     */
    private fun seed(track: Track, collection: Uri): Boolean {
        if (!Permissions.mediaGranted(context)) return false
        track.baseline = maxId(collection)
        track.watermark = track.baseline
        return true
    }

    /** 取某个集合里当前最大的 _id，作为增量水位线。 */
    private fun maxId(collection: Uri): Long {
        var max = 0L
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                null, null,
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) max = cursor.getLong(0)
            }
        }.onFailure {
            AppLog.error(TAG, "读取最大 id 失败: ${it.javaClass.simpleName} ${it.message}")
        }
        return max
    }

    /**
     * 功能自检：数一数现在能看到多少张图片。
     * 如果是「部分访问」，这里会是 0 或极少 —— 比看权限状态可靠得多。
     */
    private fun probeVisibility(): Int {
        var total = 0
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                null, null, null,
            )?.use { cursor -> total = cursor.count }
        }.onFailure {
            AppLog.error(TAG, "媒体可见性自检失败: ${it.javaClass.simpleName} ${it.message}")
        }
        AppLog.event(TAG, "媒体可见性自检：相册里能看到 $total 张图片")
        return total
    }

    /**
     * 兜底轮询。
     * ColorOS/OxygenOS 在后台会过滤 MediaStore 的 ContentObserver 通知，
     * 只靠 onChange 会漏掉文件，所以每 5 秒再主动按水位线增量扫一次。
     * 没连上对端时放慢到 15 秒（反正没人接收）。
     */
    private val pollInterval: Long
        get() = if (isConnected()) {
            POLL_INTERVAL_MS
        } else {
            POLL_IDLE_MS
        }

    private fun startPolling() {
        val h = handler ?: return
        val task = object : Runnable {
            override fun run() {
                if (!running) return
                runCatching { scanAll() }
                    .onFailure { AppLog.error(TAG, "轮询扫描失败: ${it.javaClass.simpleName} ${it.message}") }
                h.postDelayed(this, pollInterval)
            }
        }
        h.postDelayed(task, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        runCatching {
            imageObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
            }
            videoObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
            }
        }
        imageObserver = null
        videoObserver = null
        thread?.quitSafely()
        thread = null
        handler = null
        AppLog.info(TAG, "已停止监听相册")
    }

    /** 权限刚授予 / 用户手动触发时，立刻补扫一次，并重新做可见性自检。 */
    fun rescanNow() {
        val h = handler ?: return
        scanPending = false
        h.post {
            val visible = probeVisibility()
            onVisibility(visible)
            onStatus("监听中（相册里能看到 $visible 张图片）")
            runCatching { scanAll() }
                .onFailure { AppLog.error(TAG, "补扫失败: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun observer(): ContentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = scheduleScan()
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            AppLog.info(TAG, "相册变化通知：$uri")
            scheduleScan()
        }
    }

    /** 合并密集的回调，避免一张图保存时触发几十次扫描。 */
    private fun scheduleScan() {
        val h = handler ?: return
        if (scanPending) return
        scanPending = true
        h.postDelayed({
            scanPending = false
            runCatching { scanAll() }
                .onFailure { AppLog.error(TAG, "扫描失败: ${it.javaClass.simpleName} ${it.message}") }
        }, SCAN_DEBOUNCE_MS)
    }

    private fun scanAll() {
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaItem.KIND_IMAGE)
        scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaItem.KIND_VIDEO)
    }

    private fun scan(collection: Uri, kind: String) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val sdk = Build.VERSION.SDK_INT
        if (sdk >= 29) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
            projection.add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        } else {
            projection.add(MediaStore.MediaColumns.DATA)
        }
        if (kind == MediaItem.KIND_VIDEO) {
            projection.add(MediaStore.Video.Media.DURATION)
            projection.add(MediaStore.Video.Media.WIDTH)
            projection.add(MediaStore.Video.Media.HEIGHT)
        }

        val track = if (kind == MediaItem.KIND_IMAGE) images else videos
        if (track.baseline < 0) {
            // 启动时还没权限：现在有了就从这一刻开始算，这一轮不推任何东西
            if (seed(track, collection)) AppLog.info(TAG, "已拿到媒体权限，$kind 从 id=${track.baseline} 开始监听")
            return
        }
        val watermark = track.watermark
        var maxId = watermark
        val rows = mutableListOf<MediaItem>()

        // 用自增主键 _id 做增量，比 date_added 可靠（不受时钟/媒体库写入时间影响）
        val from = maxOf(track.baseline, watermark - LOOKBACK_IDS)
        val selection = "${MediaStore.MediaColumns._ID} > ?"
        val order = "${MediaStore.MediaColumns._ID} ASC"

        try {
            context.contentResolver.query(
                collection, projection.toTypedArray(), selection, arrayOf(from.toString()), order
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val durCol = if (kind == MediaItem.KIND_VIDEO) {
                    cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val added = cursor.getLong(addedCol)
                    val id = cursor.getLong(idCol)
                    if (id > maxId) maxId = id
                    if (id in track.reported) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = cursor.getString(nameCol) ?: "未命名"
                    val path = if (pathCol >= 0) {
                        cursor.getString(pathCol).orEmpty()
                    } else if (dataCol >= 0) {
                        cursor.getString(dataCol).orEmpty()
                    } else {
                        ""
                    }

                    val isShot = looksLikeScreenshot(name, path)
                    rows.add(
                        MediaItem(
                            id = "$kind-$id",
                            kind = kind,
                            displayName = name,
                            sizeBytes = cursor.getLong(sizeCol),
                            mime = cursor.getString(mimeCol) ?: "",
                            durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                            dateAddedSec = added,
                            relativePath = path,
                            contentUri = uri.toString(),
                            isScreenshot = isShot,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLog.error(TAG, "没有媒体读取权限，无法监听：${e.message}")
            return
        } catch (e: Exception) {
            AppLog.error(TAG, "查询 $kind 失败: ${e.javaClass.simpleName} ${e.message}")
            return
        }

        track.watermark = maxId
        if (rows.isEmpty()) return
        AppLog.info(TAG, "扫描 $kind：水位线=$watermark，查到 ${rows.size} 个新文件，新水位线=$maxId")
        onStatus("监听中（已发现 $seenCount 个新文件）")

        val onlyShot = onlyScreenshots()
        var skipped = 0
        rows.forEach { item ->
            // 大小还是 0 的不记为「处理过」：可能还在写，下一轮再看
            if (item.sizeBytes > 0L) markReported(track, item.id.substringAfterLast('-').toLong())
            when {
                isReceivedFile(item) -> {
                    skipped++
                }
                onlyShot && !isShot(item) -> {
                    skipped++
                }
                item.sizeBytes <= 0L -> {
                    skipped++
                }
                else -> {
                    seenCount++
                    val thumb = runCatching { makeThumb(item) }
                        .onFailure { AppLog.error(TAG, "缩略图生成失败(${item.displayName}): ${it.message}") }
                        .getOrNull()
                    if (thumb != null) {
                        AppLog.event(
                            TAG,
                            "发现新$kind：${item.displayName}（${thumb.size / 1024} KB 缩略图）"
                        )
                    } else {
                        AppLog.event(TAG, "发现新$kind：${item.displayName}（缩略图生成失败，只发元数据）")
                    }
                    onNewMedia(item, thumb)
                }
            }
        }
        if (skipped > 0) {
            AppLog.info(TAG, "跳过 $skipped 个文件（接收目录/不符合筛选条件）")
        }
    }

    private fun markReported(track: Track, id: Long) {
        track.reported.add(id)
        while (track.reported.size > MAX_REPORTED) track.reported.remove(track.reported.first())
    }

    /** 我们自己保存「从对端收到的文件」的目录：必须排除，否则会无限来回。 */
    private fun isReceivedFile(item: MediaItem): Boolean =
        item.relativePath.contains(RECEIVED_DIR, ignoreCase = true) ||
            item.displayName.contains(RECEIVED_DIR, ignoreCase = true)

    private fun looksLikeScreenshot(name: String, path: String): Boolean {
        val p = path.lowercase(Locale.US)
        val n = name.lowercase(Locale.US)
        return p.contains("screenshot") || p.contains("截图") ||
            n.startsWith("screenshot") || n.startsWith("截图") ||
            n.contains("截屏")
    }

    private fun isShot(item: MediaItem): Boolean = looksLikeScreenshot(item.displayName, item.relativePath)

    // ---------------------------------------------------------------- 缩略图

    private fun makeThumb(item: MediaItem): ByteArray? {
        val uri = Uri.parse(item.contentUri)
        val bitmap = (if (item.isVideo) frameOfVideo(uri) else decodeImage(uri)) ?: return null
        return try {
            compressToJpeg(bitmap, THUMB_MAX_DIM, THUMB_MAX_BYTES)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > THUMB_MAX_DIM * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun frameOfVideo(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            AppLog.error(TAG, "取视频首帧失败: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compressToJpeg(source: Bitmap, maxDim: Int, maxBytes: Int): ByteArray {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(source.width, source.height))
        val scaled = if (scale >= 1f) {
            source
        } else {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }

        return try {
            val buffer = ByteArrayOutputStream()
            var quality = 75
            var best: ByteArray? = null
            while (true) {
                buffer.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                val bytes = buffer.toByteArray()
                if (best == null || bytes.size < best!!.size) best = bytes
                if (bytes.size <= maxBytes || quality <= 30) break
                quality -= 12
            }
            best ?: ByteArray(0)
        } finally {
            if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        }
    }
}
