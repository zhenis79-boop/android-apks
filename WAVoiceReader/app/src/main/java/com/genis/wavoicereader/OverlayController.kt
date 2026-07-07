package com.genis.wavoicereader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Показывает плавающие карточки с текстом поверх других приложений.
 * Требует разрешение "Отображение поверх других приложений" (SYSTEM_ALERT_WINDOW).
 *
 * Поведение карточки:
 *  - крестик ✕ в углу закрывает её сразу;
 *  - тап по тексту копирует его в буфер обмена;
 *  - если ничего не нажали, карточка сама исчезает через autoHideMs (по умолчанию 1 минута).
 */
class OverlayController(private val context: Context) {

    companion object {
        const val DEFAULT_AUTO_HIDE_MS = 60_000L
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var container: LinearLayout? = null
    private val hideRunnables = HashMap<View, Runnable>()

    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    fun showMessage(title: String, text: String, autoHideMs: Long = DEFAULT_AUTO_HIDE_MS) {
        if (!hasPermission()) return
        mainHandler.post {
            ensureContainer()
            val card = buildCard(title, text)
            container?.addView(card, 0)
            val hideRunnable = Runnable { removeCard(card) }
            hideRunnables[card] = hideRunnable
            mainHandler.postDelayed(hideRunnable, autoHideMs)
        }
    }

    private fun ensureContainer() {
        if (container != null) return

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        // Смещаем ниже, чтобы карточку не перекрывала шторка уведомления WhatsApp сверху.
        params.y = (150 * context.resources.displayMetrics.density).toInt()

        windowManager.addView(layout, params)
        container = layout
    }

    private fun buildCard(title: String, text: String): View {
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val margin = (12 * density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE202124"))
                cornerRadius = 24f
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(margin, margin, margin, 0)
        card.layoutParams = lp

        // Верхняя строка: заголовок + крестик
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            setText(title)
            setTextColor(Color.parseColor("#25D366")) // цвет WhatsApp
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val closeBtn = TextView(context).apply {
            setText("✕")
            setTextColor(Color.WHITE)
            textSize = 20f
            val p = (8 * density).toInt()
            setPadding(p, 0, p, 0)
            isClickable = true
            setOnClickListener { removeCard(card) }
        }
        header.addView(titleView)
        header.addView(closeBtn)

        val textView = TextView(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, (6 * density).toInt(), 0, 0)
            isClickable = true
            setOnClickListener { copyToClipboard(text) }
        }

        card.addView(header)
        card.addView(textView)
        return card
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Голосовое", text))
            Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun removeCard(view: View) {
        mainHandler.post {
            val c = container ?: return@post
            hideRunnables.remove(view)?.let { mainHandler.removeCallbacks(it) }
            if (view.parent == c) c.removeView(view)
            // если карточек не осталось — убираем контейнер из окна, чтобы он не висел вечно
            if (c.childCount == 0) {
                try {
                    windowManager.removeView(c)
                } catch (_: Exception) {
                }
                container = null
            }
        }
    }
}
