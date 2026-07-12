package com.genis.wavoicereader

import java.util.Locale

/**
 * Живые счётчики по этапам цепочки обработки голосового — чтобы на главном
 * экране сразу видеть, на каком этапе что-то застряло, без вычитки сырого лога.
 *
 * Этапы идут в порядке реального потока:
 *   файл-кандидат пойман → WA-уведомление записано → match отправителя →
 *   распознавание (успех/ошибка).
 *
 * Хранится только в памяти процесса; не переживает перезапуск приложения.
 * Это намеренно: нас интересует «что происходит прямо сейчас», а не
 * историческая статистика (для истории есть HistoryStore).
 */
object PipelineStats {

    @Volatile private var candidatesCaught = 0
    @Volatile private var lastCandidateAt = 0L
    // Сколько времени файл ждал, прежде чем наблюдатель его заметил (между mtime файла
    // и моментом вызова onCandidateCaught). Большое число → виноват пропуск FileObserver
    // и медленный polling, а не сеть/OpenAI.
    @Volatile private var lastCatchLagMs = 0L

    @Volatile private var waNotifications = 0
    @Volatile private var lastNotificationAt = 0L
    // Разбивка: сколько из WA-уведомлений распознаны как «голосовые» (прошли voiceMarkers),
    // а сколько — прочие (текстовые/системные). Если голосовых 0 при waNotifications > 0,
    // значит фильтр по тексту уведомления не срабатывает на этом устройстве.
    @Volatile private var waVoiceNotifications = 0
    @Volatile private var waOtherNotifications = 0

    @Volatile private var senderMatched = 0
    @Volatile private var senderUnmatched = 0
    @Volatile private var lastSenderDecisionAt = 0L

    @Volatile private var transcribeOk = 0
    @Volatile private var transcribeError = 0
    @Volatile private var lastTranscribeAt = 0L
    // Время от старта обработки пойманного файла до показа карточки (ожидание стабильности
    // файла + match отправителя + сетевой запрос к OpenAI). Большое число при маленькой
    // задержке ловли → виновата сеть/OpenAI.
    @Volatile private var lastProcessLagMs = 0L

    @Synchronized
    fun onCandidateCaught(fileMtime: Long = 0L) {
        candidatesCaught++
        val now = System.currentTimeMillis()
        lastCandidateAt = now
        // mtime = 0 (тестовый вызов/неизвестно) → лаг не считаем.
        lastCatchLagMs = if (fileMtime > 0) (now - fileMtime).coerceAtLeast(0L) else lastCatchLagMs
    }

    @Synchronized
    fun onWaNotification(isVoice: Boolean) {
        waNotifications++
        lastNotificationAt = System.currentTimeMillis()
        if (isVoice) waVoiceNotifications++ else waOtherNotifications++
    }

    /** Успешный match отправителя по времени. */
    @Synchronized
    fun onSenderMatched() {
        senderMatched++
        lastSenderDecisionAt = System.currentTimeMillis()
    }

    /** Файл распознан, но подходящего уведомления не нашлось. */
    @Synchronized
    fun onSenderUnmatched() {
        senderUnmatched++
        lastSenderDecisionAt = System.currentTimeMillis()
    }

    @Synchronized
    fun onTranscribeOk(processLagMs: Long = 0L) {
        transcribeOk++
        lastTranscribeAt = System.currentTimeMillis()
        if (processLagMs > 0) lastProcessLagMs = processLagMs
    }

    @Synchronized
    fun onTranscribeError(processLagMs: Long = 0L) {
        transcribeError++
        lastTranscribeAt = System.currentTimeMillis()
        if (processLagMs > 0) lastProcessLagMs = processLagMs
    }

    /** Снимок для показа в UI; читается из основного потока в autoRefresh. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        candidatesCaught = candidatesCaught,
        lastCandidateAt = lastCandidateAt,
        lastCatchLagMs = lastCatchLagMs,
        waNotifications = waNotifications,
        lastNotificationAt = lastNotificationAt,
        waVoiceNotifications = waVoiceNotifications,
        waOtherNotifications = waOtherNotifications,
        senderMatched = senderMatched,
        senderUnmatched = senderUnmatched,
        lastSenderDecisionAt = lastSenderDecisionAt,
        transcribeOk = transcribeOk,
        transcribeError = transcribeError,
        lastTranscribeAt = lastTranscribeAt,
        lastProcessLagMs = lastProcessLagMs,
    )

    @Synchronized
    fun reset() {
        candidatesCaught = 0
        lastCandidateAt = 0L
        lastCatchLagMs = 0L
        waNotifications = 0
        lastNotificationAt = 0L
        waVoiceNotifications = 0
        waOtherNotifications = 0
        senderMatched = 0
        senderUnmatched = 0
        lastSenderDecisionAt = 0L
        transcribeOk = 0
        transcribeError = 0
        lastTranscribeAt = 0L
        lastProcessLagMs = 0L
    }

    data class Snapshot(
        val candidatesCaught: Int,
        val lastCandidateAt: Long,
        val lastCatchLagMs: Long,
        val waNotifications: Int,
        val lastNotificationAt: Long,
        val waVoiceNotifications: Int,
        val waOtherNotifications: Int,
        val senderMatched: Int,
        val senderUnmatched: Int,
        val lastSenderDecisionAt: Long,
        val transcribeOk: Int,
        val transcribeError: Int,
        val lastTranscribeAt: Long,
        val lastProcessLagMs: Long,
    )

    /** Превращает epoch-миллисекунды в «Nс/мин назад», либо «—», если события не было. */
    fun agoLabel(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return "—"
        val delta = (now - ts) / 1000
        return when {
            delta < 60 -> "${delta}с назад"
            delta < 3600 -> "${delta / 60}мин назад"
            else -> "${delta / 3600}ч назад"
        }
    }

    /** Превращает длительность в мс в «0.9с» / «52.0с» / «2.3мин», либо «—» для 0. */
    fun lagLabel(ms: Long): String {
        if (ms <= 0L) return "—"
        val sec = ms / 1000.0
        return when {
            sec < 60 -> String.format(Locale.US, "%.1fс", sec)
            else -> String.format(Locale.US, "%.1fмин", sec / 60.0)
        }
    }
}
