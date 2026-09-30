package com.opposhot.link.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简的内存日志：同时镜像到 logcat，并在主线程回调给 UI。
 * Phase 0 不做落盘，不引入任何第三方库。
 */
object AppLog {

    enum class Level { INFO, EVENT, TX, RX, ERROR }

    data class Entry(
        val time: String,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 400
    private const val MAX_MESSAGE_CHARS = 300
    private const val LOGCAT_TAG = "OppoShotLink"

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(Entry) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun info(tag: String, message: String) = add(Level.INFO, tag, message)
    fun event(tag: String, message: String) = add(Level.EVENT, tag, message)
    fun tx(tag: String, message: String) = add(Level.TX, tag, message)
    fun rx(tag: String, message: String) = add(Level.RX, tag, message)
    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    fun add(level: Level, tag: String, message: String) {
        // 长文本一律截断：日志留在内存里、还要渲染到界面上，太长会把内存和主线程拖死。
        val text = if (message.length > MAX_MESSAGE_CHARS) {
            message.take(MAX_MESSAGE_CHARS) + "…"
        } else {
            message
        }
        val entry = Entry(format.format(Date()), level, tag, text)
        synchronized(this) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        Log.println(logcatPriority(level), LOGCAT_TAG, "[$tag] $text")
        mainHandler.post {
            val snapshot = synchronized(this) { listeners.toList() }
            snapshot.forEach { it(entry) }
        }
    }

    fun snapshot(): List<Entry> = synchronized(this) { entries.toList() }

    fun clear() = synchronized(this) { entries.clear() }

    fun addListener(listener: (Entry) -> Unit) = synchronized(this) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Entry) -> Unit) = synchronized(this) {
        listeners.remove(listener)
    }

    private fun logcatPriority(level: Level): Int = when (level) {
        Level.ERROR -> Log.ERROR
        Level.RX, Level.TX, Level.EVENT -> Log.INFO
        Level.INFO -> Log.DEBUG
    }
}
