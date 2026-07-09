package com.genis.wavoicereader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * Обычный ScrollView внутри другого ScrollView жестом не прокручивается — внешний
 * перехватывает вертикальный drag раньше, чем внутренний успевает его обработать.
 * Пока палец внутри этого блока, явно запрещаем родителю перехват события.
 */
class InnerScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }
}
