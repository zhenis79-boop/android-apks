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

    // Уведомление и файл голосового появляются практически одновременно (обычно в пределах
    // пары секунд). Узкое окно + одноразовое использование не даёт старому входящему
    // уведомлению ложно "прицепиться" к более позднему файлу — например, к вашему
    // собственному исходящему голосовому, отправленному через пару минут после входящего.
    private const val MATCH_TOLERANCE_MS = 25_000L
    private const val KEEP_MS = 2 * 60_000L

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
        Logger.i("SenderTracker", "record: \"$title\" isVoice=$isVoice")
        val cutoff = time - KEEP_MS
        recs.removeAll { it.time < cutoff }
    }

    /**
     * Ищет входящее голосовое уведомление рядом по времени с появлением файла.
     * Совпадение одноразовое — найденная запись удаляется, чтобы не сматчиться повторно
     * с другим файлом.
     */
    @Synchronized
    fun matchIncomingVoice(fileTime: Long): Rec? {
        val candidate = recs
            .filter { it.isVoice && kotlin.math.abs(it.time - fileTime) <= MATCH_TOLERANCE_MS }
            .minByOrNull { kotlin.math.abs(it.time - fileTime) }
        if (candidate != null) recs.remove(candidate)
        return candidate
    }
}
