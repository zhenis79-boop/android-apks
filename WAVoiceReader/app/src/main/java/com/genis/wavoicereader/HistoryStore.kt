package com.genis.wavoicereader

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * История распознанных голосовых. Хранит последние MAX_ENTRIES записей
 * в filesDir/history.txt. Формат строки: epochMillis \t текст (переводы строк экранированы).
 */
object HistoryStore {

    private const val FILE_NAME = "history.txt"
    private const val MAX_ENTRIES = 50
    private val TS = SimpleDateFormat("dd.MM HH:mm", Locale.US)

    data class Entry(val time: Long, val text: String)

    @Synchronized
    fun add(context: Context, text: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val safe = text.replace("\n", "\\n").replace("\t", " ")
            file.appendText("${System.currentTimeMillis()}\t$safe\n")
            val lines = file.readLines()
            if (lines.size > MAX_ENTRIES) {
                file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun all(context: Context): List<Entry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx <= 0) return@mapNotNull null
                val t = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                val txt = line.substring(idx + 1).replace("\\n", "\n")
                Entry(t, txt)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Форматирует историю для показа/копирования (новые сверху). */
    fun formatted(context: Context): String {
        val entries = all(context)
        if (entries.isEmpty()) return "(история пуста)"
        return entries.reversed().joinToString("\n\n") { e ->
            "[${TS.format(Date(e.time))}] ${e.text}"
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).writeText("")
        } catch (_: Exception) {
        }
    }
}
