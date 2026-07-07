package com.genis.wavoicereader

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Слушает уведомления WhatsApp, чтобы определить отправителя входящего голосового.
 * Требует разрешение "Доступ к уведомлениям" (включается вручную в настройках Android).
 */
class WaNotificationListener : NotificationListenerService() {

    companion object {
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn?.notification ?: return
        if (sbn.packageName !in WA_PACKAGES) return

        // Не фильтруем здесь по category/isOngoing/progress-флагам: разные версии WhatsApp
        // ставят их непредсказуемо (в том числе на настоящие голосовые уведомления, у которых
        // есть встроенный плеер с прогресс-баром воспроизведения), и такой фильтр однажды
        // случайно отсеял вообще все голосовые уведомления. Мусорные системные уведомления
        // (резервное копирование и т.п.) сами по себе отсеиваются ниже, в SenderTracker, по
        // содержимому текста (voiceMarkers) — их текст никогда не совпадёт с "голосовым".
        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        if (title.isEmpty() || title.equals("WhatsApp", ignoreCase = true)) return // сводка, не отправитель

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        SenderTracker.record(title, text, System.currentTimeMillis())
    }
}
