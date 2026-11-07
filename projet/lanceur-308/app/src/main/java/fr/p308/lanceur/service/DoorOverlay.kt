package fr.p308.lanceur.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Bandeau flottant affiché par-dessus CarPlay quand un ouvrant est ouvert. */
class DoorOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show(text: String) {
        if (!Settings.canDrawOverlays(ctx)) return
        val v = view ?: TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(0xE6B71C1C.toInt())
            }
            setOnClickListener { hide() }
        }.also {
            wm.addView(it, layoutParams())
            view = it
        }
        v.text = text
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 40
    }
}
