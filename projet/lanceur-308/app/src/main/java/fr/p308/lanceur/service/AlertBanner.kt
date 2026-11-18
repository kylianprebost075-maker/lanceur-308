package fr.p308.lanceur.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Bandeau d'alerte (batterie…) par-dessus CarPlay, qui disparaît seul après quelques secondes. */
class AlertBanner(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val LIVE = "live"

    fun show(text: String, color: Int, durationMs: Long = 10_000) {
        if (!Settings.canDrawOverlays(ctx)) return
        hide()
        val v = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(48, 24, 48, 24)
            this.text = text
            background = GradientDrawable().apply { cornerRadius = 48f; setColor(color) }
            setOnClickListener { hide() }
        }
        wm.addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 130 })
        view = v
        handler.postDelayed(::hide, durationMs)
    }

    /**
     * v0.47 : bandeau qui reste affiché et dont le texte change (compte à rebours du turbo), sans clignoter : il est
     * créé la première fois puis seulement mis à jour. Enlevé par [hide].
     */
    fun live(text: String, color: Int) {
        if (!Settings.canDrawOverlays(ctx)) return
        handler.removeCallbacksAndMessages(null)
        val current = view
        if (current != null && current.tag == LIVE) {
            if (current.text != text) current.text = text
            (current.background as? GradientDrawable)?.setColor(color)
            return
        }
        show(text, color, Long.MAX_VALUE / 4)
        handler.removeCallbacksAndMessages(null)
        view?.tag = LIVE
    }

    /** Vrai si le bandeau affiché est celui de [live]. */
    val isLive: Boolean get() = view?.tag == LIVE

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
