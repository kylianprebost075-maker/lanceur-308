package fr.p308.lanceur.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import fr.p308.lanceur.vehicle.EngineState
import fr.p308.lanceur.vehicle.ShiftAdvice
import java.util.Locale

/**
 * Panneau « performances » posé sur un côté de l'écran pendant CarPlay (fenêtre flottante, comme DoorOverlay) :
 * puissance et couple estimés, rapport et conseil de passage, régime, eau, turbo, batterie.
 * Toucher = réduire / agrandir ; appui long = fermer jusqu'au prochain lancement de CarPlay.
 * Les touchers hors du panneau vont à CarPlay.
 */
class PerfOverlay(private val ctx: Context, private val onClose: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var full: LinearLayout? = null
    private var mini: TextView? = null
    private var rightSide = true
    private var collapsed = false

    private lateinit var power: TextView
    private lateinit var torque: TextView
    private lateinit var gear: TextView
    private lateinit var advice: TextView
    private lateinit var rpm: TextView
    private lateinit var coolant: TextView
    private lateinit var boost: TextView
    private lateinit var volts: TextView
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * density).toInt()

    fun show(e: EngineState, onRight: Boolean) {
        if (!Settings.canDrawOverlays(ctx)) return
        if (root != null && onRight != rightSide) hide()
        rightSide = onRight
        if (root == null) build()
        render(e)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun build() {
        val sub = 0xFF8C97B8.toInt()
        power = label("—", 44f, bold = true)
        torque = label("", 16f, sub)
        gear = label("", 30f, bold = true)
        advice = label("", 17f, bold = true)
        rpm = label("", 18f)
        coolant = label("", 18f)
        boost = label("", 18f)
        volts = label("", 18f)
        status = label("", 12f, sub)
        val f = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label("Puissance", 13f, sub))
            addView(power)
            addView(torque)
            addView(View(ctx), LinearLayout.LayoutParams(1, dp(10)))
            addView(label("Rapport", 13f, sub))
            addView(gear)
            addView(advice)
            addView(View(ctx), LinearLayout.LayoutParams(1, dp(10)))
            listOf(rpm, coolant, boost, volts).forEach { addView(it) }
            addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
            addView(status)
        }
        val m = label("", 22f, bold = true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xEB0A0F1E.toInt())
            }
            addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(m)
            setOnClickListener { toggle() }
            setOnLongClickListener { hide(); onClose(); true }
        }
        full = f
        mini = m
        root = r
        applyCollapsed()
        wm.addView(r, params())
    }

    private fun toggle() {
        collapsed = !collapsed
        applyCollapsed()
        root?.let { runCatching { wm.updateViewLayout(it, params()) } }
    }

    private fun applyCollapsed() {
        full?.visibility = if (collapsed) View.GONE else View.VISIBLE
        mini?.visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun params() = WindowManager.LayoutParams(
        if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else dp(200),
        if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.TOP
        x = dp(6)
        y = dp(6)
    }

    private fun render(e: EngineState) {
        val a = e.advice
        val adviceColor = when (a.kind) {
            ShiftAdvice.Kind.UP -> 0xFF7CFC3A.toInt()
            ShiftAdvice.Kind.DOWN -> 0xFFFFA726.toInt()
            ShiftAdvice.Kind.COLD -> 0xFF64B5F6.toInt()
            ShiftAdvice.Kind.NONE -> Color.WHITE
        }
        power.text = "${e.powerCh ?: "—"} ch"
        torque.text = e.torqueNm?.let { "$it Nm  ·  pic ${e.peakCh} ch" } ?: "estimé (OBD)"
        gear.text = a.gear?.let { ShiftAdvice.ordinal(it) } ?: "—"
        advice.text = a.text
        advice.setTextColor(adviceColor)
        rpm.text = "Régime  ${e.rpm ?: "—"} tr/min"
        coolant.text = "Eau  ${e.coolantC?.let { "$it °C" } ?: "—"}"
        coolant.setTextColor(
            when {
                e.coolantC == null -> Color.WHITE
                e.coolantC >= 105 -> 0xFFFF4D4D.toInt()
                e.coolantC < 60 -> 0xFF64B5F6.toInt()
                else -> Color.WHITE
            }
        )
        boost.text = "Turbo  ${e.boostBar?.let { "%.2f bar".format(Locale.FRANCE, it) } ?: "—"}"
        volts.text = "Batterie  ${e.voltage?.let { "%.1f V".format(Locale.FRANCE, it) } ?: "—"}"
        status.text = if (e.connected) "Appui long : fermer" else e.status
        mini?.text = when (a.kind) {
            ShiftAdvice.Kind.UP -> "▲ ${ShiftAdvice.ordinal(a.target)}  ${e.powerCh ?: "—"} ch"
            ShiftAdvice.Kind.DOWN -> "▼ ${ShiftAdvice.ordinal(a.target)}  ${e.powerCh ?: "—"} ch"
            else -> "${e.powerCh ?: "—"} ch  ${e.coolantC?.let { "$it °C" } ?: ""}"
        }
        mini?.setTextColor(if (a.kind == ShiftAdvice.Kind.NONE) Color.WHITE else adviceColor)
    }
}
