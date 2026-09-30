package com.opposhot.link.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 生成一张测试图片并真正写进相册，用来端到端验证
 * 「保存新文件 → MediaStore 变化 → 推给对端 → 对端弹通知」这条链路。
 *
 * 写在 Pictures/OppoShotTest/，不是「接收目录」，所以不会被防循环逻辑过滤掉。
 */
object DemoMedia {

    private const val TAG = "DemoMedia"
    private const val DIR = "OppoShotTest"

    fun saveTestImage(context: Context): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val name = "OppoShotTest_$stamp.jpg"
        val bitmap = render(stamp)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, "/sdcard/Pictures/$DIR/$name")
            }
        }

        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                AppLog.error(TAG, "写入相册失败：MediaStore insert 返回空（可能缺权限）")
                return false
            }

        return try {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, buffer)
            resolver.openOutputStream(uri)?.use { it.write(buffer.toByteArray()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            AppLog.event(TAG, "已写入一张测试图片：Pictures/$DIR/$name（等 1~2 秒会被监听到）")
            true
        } catch (e: Exception) {
            AppLog.error(TAG, "写入测试图片失败: ${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            false
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun render(stamp: String): Bitmap {
        val width = 1080
        val height = 720
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#123B7A"))

        val barPaint = Paint().apply { color = Color.parseColor("#4CAF50") }
        val rnd = java.util.Random(stamp.hashCode().toLong())
        for (i in 0 until 6) {
            val top = 60 + i * 100
            barPaint.color = Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.7f, 0.9f))
            canvas.drawRect(60f, top.toFloat(), (60 + 200 + rnd.nextInt(700)).toFloat(), (top + 70).toFloat(), barPaint)
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            isAntiAlias = true
        }
        canvas.drawText("OppoShot Link", 60f, 60f, textPaint)
        canvas.drawText(stamp.replace('_', ' '), 60f, 120f, textPaint)
        return bitmap
    }
}
