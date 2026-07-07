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
        private const val TAG = "NotifListener"
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logger.init(applicationContext)
        Logger.i(TAG, "Доступ к уведомлениям подключён")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn?.notification ?: return
        if (sbn.packageName !in WA_PACKAGES) return

        // Служебные уведомления (резервное копирование, синхронизация, звонки) — не сообщения:
        // они либо ongoing (несворачиваемые, с прогресс-баром), либо явно не CATEGORY_MESSAGE.
        if (sbn.isOngoing) return
        val category = notif.category
        if (category != null && category != Notification.CATEGORY_MESSAGE) return

        val extras = notif.extras
        // Прогресс-уведомления (например, "Отправка: X МБ из Y МБ") всегда несут EXTRA_PROGRESS_MAX.
        if (extras.containsKey(Notification.EXTRA_PROGRESS_MAX)) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        if (title.isEmpty() || title.equals("WhatsApp", ignoreCase = true)) return // сводка, не отправитель

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        Logger.init(applicationContext)
        SenderTracker.record(title, text, System.currentTimeMillis())
        Logger.i(TAG, "WhatsApp уведомление: \"$title\" — \"$text\"")
    }
}
