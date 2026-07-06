package com.genis.wavoicereader

/**
 * Запоминает последние уведомления WhatsApp, чтобы по времени сопоставить
 * входящее голосовое сообщение с его отправителем.
 *
 * Ключевая идея: ВХОДЯЩЕЕ голосовое всегда создаёт уведомление WhatsApp (с именем
 * отправителя), а ВАШЕ исходящее — нет. Поэтому наличие подходящего голосового
 * уведомления = «это входящее, вот от кого»; отсутствие = «это ваше исходящее, пропускаем».
 */
object SenderTracker {

    private const val WINDOW_MS = 6 * 60_000L

    data class Rec(val title: String, val text: String, val time: Long, val isVoice: Boolean)

    private val recs = ArrayList<Rec>()

    // Признаки того, что уведомление относится к голосовому/аудио (разные языки и эмодзи).
    private val voiceMarkers = listOf(
        "🎤", // 🎤
        "🎙", // 🎙
        "🔊", // 🔊
        "voice", "audio",
        "голос", "аудио",
        "дауыс"
    )

    @Synchronized
    fun record(title: String, text: String, time: Long) {
        val isVoice = voiceMarkers.any { text.contains(it, ignoreCase = true) }
        recs.add(Rec(title, text, time, isVoice))
        val cutoff = time - WINDOW_MS * 2
        recs.removeAll { it.time < cutoff }
    }

    /** Ищет входящее голосовое уведомление рядом по времени с появлением файла. */
    @Synchronized
    fun matchIncomingVoice(fileTime: Long): Rec? {
        val lo = fileTime - WINDOW_MS
        val hi = System.currentTimeMillis() + 2_000
        return recs.filter { it.isVoice && it.time in lo..hi }.maxByOrNull { it.time }
    }
}
