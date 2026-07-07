package com.genis.wavoicereader

import android.content.Context

object Prefs {
    private const val NAME = "wa_voice_reader_prefs"
    private const val KEY_API = "openai_api_key"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_NOTIF_LISTENER_CONNECTED_AT = "notif_listener_connected_at"

    /** Момент, когда система реально подключила наш NotificationListener (не просто выдала разрешение). */
    fun setNotifListenerConnected(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_NOTIF_LISTENER_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    fun getNotifListenerConnectedAt(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_NOTIF_LISTENER_CONNECTED_AT, 0L)

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_API, null)

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_API, key).apply()
    }

    fun isServiceEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }
}
