package com.genis.wavoicereader

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Проверка, включён ли для нашего listener'а «Доступ к уведомлениям». */
object NotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val flat = try {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        } catch (_: Exception) {
            null
        } ?: return false
        val cn = ComponentName(context, WaNotificationListener::class.java)
        return flat.split(":").any { entry ->
            val c = ComponentName.unflattenFromString(entry)
            c != null && c.packageName == cn.packageName && c.className == cn.className
        }
    }
}
