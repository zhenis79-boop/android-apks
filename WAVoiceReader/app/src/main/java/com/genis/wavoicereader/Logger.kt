package com.genis.wavoicereader

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простое файловое логирование с возможностью скопировать логи из UI.
 * Пишет в filesDir/wavoicereader.log, держит только последние MAX_LINES строк.
 */
object Logger {

    private const val FILE_NAME = "wavoicereader.log"
    private const val MAX_LINES = 300
    private val TS = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    /** Вызывать один раз при старте (Activity/Service) до записи логов. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    @Synchronized
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I", tag, msg)
    }

    @Synchronized
    fun e(tag: String, msg: String, err: Throwable? = null) {
        Log.e(tag, msg, err)
        val full = if (err != null) "$msg :: ${err.javaClass.simpleName}: ${err.message}" else msg
        append("E", tag, full)
    }

    private fun append(level: String, tag: String, msg: String) {
        val ctx = appContext ?: return
        try {
            val line = "${TS.format(Date())} $level/$tag: $msg"
            val file = File(ctx.filesDir, FILE_NAME)
            file.appendText(line + "\n")
            trimIfNeeded(file)
        } catch (_: Exception) {
            // логирование не должно ронять приложение
        }
    }

    private fun trimIfNeeded(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                val trimmed = lines.takeLast(MAX_LINES).joinToString("\n")
                file.writeText(trimmed + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): String {
        val ctx = appContext ?: return "(лог недоступен)"
        val file = File(ctx.filesDir, FILE_NAME)
        return try {
            if (file.exists()) file.readText().ifBlank { "(лог пуст)" } else "(лог пуст)"
        } catch (e: Exception) {
            "(ошибка чтения лога: ${e.message})"
        }
    }

    @Synchronized
    fun clear() {
        val ctx = appContext ?: return
        try {
            File(ctx.filesDir, FILE_NAME).writeText("")
        } catch (_: Exception) {
        }
    }
}
