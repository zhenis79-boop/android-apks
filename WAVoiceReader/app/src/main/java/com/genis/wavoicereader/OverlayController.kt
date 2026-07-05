package com.genis.wavoicereader

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

/**
 * Показывает плавающие карточки с текстом поверх других приложений.
 * Требует разрешение "Отображение поверх других приложений" (SYSTEM_ALERT_WINDOW).
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var container: LinearLayout? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    fun showMessage(title: String, text: String, autoHideMs: Long = 25_000L) {
        if (!hasPermission()) return
        mainHandler.post {
            ensureContainer()
            val card = buildCard(title, text)
            container?.addView(card, 0)
            mainHandler.postDelayed({ removeCard(card) }, autoHideMs)
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
        params.y = 60

        windowManager.addView(layout, params)
        container = layout
    }

    private fun buildCard(title: String, text: String): View {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val margin = (12 * context.resources.displayMetrics.density).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD202124"))
                cornerRadius = 24f
            }
            isClickable = true
            setOnClickListener { removeCard(this) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(margin, margin, margin, 0)
        card.layoutParams = lp

        val titleView = TextView(context).apply {
            setText(title)
            setTextColor(Color.parseColor("#25D366")) // цвет WhatsApp
            textSize = 13f
            setPadding(0, 0, 0, 6)
        }
        val textView = TextView(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val hint = TextView(context).apply {
            setText("нажмите, чтобы закрыть")
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 11f
            setPadding(0, 8, 0, 0)
        }

        card.addView(titleView)
        card.addView(textView)
        card.addView(hint)
        return card
    }

    private fun removeCard(view: View) {
        container?.let {
            if (view.parent == it) it.removeView(view)
        }
    }
}
