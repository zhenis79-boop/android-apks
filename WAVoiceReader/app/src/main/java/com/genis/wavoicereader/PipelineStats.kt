package com.genis.wavoicereader

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

    @Volatile private var waNotifications = 0
    @Volatile private var lastNotificationAt = 0L

    @Volatile private var senderMatched = 0
    @Volatile private var senderUnmatched = 0
    @Volatile private var lastSenderDecisionAt = 0L

    @Volatile private var transcribeOk = 0
    @Volatile private var transcribeError = 0
    @Volatile private var lastTranscribeAt = 0L

    @Synchronized
    fun onCandidateCaught() {
        candidatesCaught++
        lastCandidateAt = System.currentTimeMillis()
    }

    @Synchronized
    fun onWaNotification() {
        waNotifications++
        lastNotificationAt = System.currentTimeMillis()
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
    fun onTranscribeOk() {
        transcribeOk++
        lastTranscribeAt = System.currentTimeMillis()
    }

    @Synchronized
    fun onTranscribeError() {
        transcribeError++
        lastTranscribeAt = System.currentTimeMillis()
    }

    /** Снимок для показа в UI; читается из основного потока в autoRefresh. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        candidatesCaught = candidatesCaught,
        lastCandidateAt = lastCandidateAt,
        waNotifications = waNotifications,
        lastNotificationAt = lastNotificationAt,
        senderMatched = senderMatched,
        senderUnmatched = senderUnmatched,
        lastSenderDecisionAt = lastSenderDecisionAt,
        transcribeOk = transcribeOk,
        transcribeError = transcribeError,
        lastTranscribeAt = lastTranscribeAt,
    )

    @Synchronized
    fun reset() {
        candidatesCaught = 0
        lastCandidateAt = 0L
        waNotifications = 0
        lastNotificationAt = 0L
        senderMatched = 0
        senderUnmatched = 0
        lastSenderDecisionAt = 0L
        transcribeOk = 0
        transcribeError = 0
        lastTranscribeAt = 0L
    }

    data class Snapshot(
        val candidatesCaught: Int,
        val lastCandidateAt: Long,
        val waNotifications: Int,
        val lastNotificationAt: Long,
        val senderMatched: Int,
        val senderUnmatched: Int,
        val lastSenderDecisionAt: Long,
        val transcribeOk: Int,
        val transcribeError: Int,
        val lastTranscribeAt: Long,
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
}
