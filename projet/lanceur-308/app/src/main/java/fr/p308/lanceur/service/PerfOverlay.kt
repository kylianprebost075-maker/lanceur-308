package fr.p308.lanceur.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import fr.p308.lanceur.vehicle.EngineState
import fr.p308.lanceur.vehicle.ShiftAdvice
import fr.p308.lanceur.vehicle.Warmup
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Panneau « performances » posé sur un côté de l'écran pendant CarPlay (fenêtre flottante, comme DoorOverlay),
 * style verre comme le Lanceur : rapport et conseil de passage, puissance et couple, temps de chauffe restant,
 * puis eau, huile (estimée), turbo et régime avec une barre colorée chacun, et la batterie.
 * Toucher = réduire / agrandir ; appui long = fermer jusqu'au prochain lancement de CarPlay.
 * Les touchers hors du panneau vont à CarPlay.
 */
class PerfOverlay(private val ctx: Context, private val onClose: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var full: LinearLayout? = null
    private var mini: LinearLayout? = null
    private var rightSide = true
    private val collapsed get() = fr.p308.lanceur.UiPrefs.perfCompact

    // Pastille (version réduite) : point de température, rapport, conseil, puissance, eau, chauffe restante.
    private lateinit var miniDot: GradientDrawable
    private lateinit var miniGear: TextView
    private lateinit var miniAdvice: TextView
    private lateinit var miniPower: TextView
    private lateinit var miniWater: TextView
    private lateinit var miniWarm: TextView
    private lateinit var columnBg: GradientDrawable
    private lateinit var pillBg: GradientDrawable

    private lateinit var gear: TextView
    private lateinit var advice: TextView
    private lateinit var power: TextView
    private lateinit var torque: TextView
    private lateinit var warmTitle: TextView
    private lateinit var warmBar: Bar
    private lateinit var warmSub: TextView
    private lateinit var coolant: Gauge
    private lateinit var oil: Gauge
    private lateinit var boost: Gauge
    private lateinit var rpm: Gauge
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * density).toInt()

    fun show(e: EngineState, onRight: Boolean) {
        if (!Settings.canDrawOverlays(ctx)) return
        if (root != null && onRight != rightSide) hide()
        rightSide = onRight
        if (root == null) build()
        else if (shownCompact != collapsed) {
            // Taille changée dans les Réglages pendant que le panneau est affiché.
            applyCollapsed()
            root?.let { runCatching { wm.updateViewLayout(it, params()) } }
        }
        render(e)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun label(text: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    /** Barre de progression arrondie (fond discret, remplissage coloré). */
    private class Bar(ctx: Context) : View(ctx) {
        var fraction = 0f
            set(v) { if (field != v) { field = v; invalidate() } }
        var color = ACCENT
            set(v) { if (field != v) { field = v; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = TRACK
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) {
                paint.color = color
                rect.set(0f, 0f, (width * f).coerceAtLeast(height.toFloat()), height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    /** Une ligne « nom ……… valeur » avec sa barre dessous. */
    private inner class Gauge(name: String) {
        val value = label("—", 19f, bold = true)
        val bar = Bar(ctx)
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(name, 14f, SUB), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            }
            addView(row)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(5) })
        }

        fun set(text: String, fraction: Float, color: Int) {
            value.text = text
            bar.fraction = fraction
            bar.color = color
        }
    }

    private fun card(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0x14FFFFFF)
            setStroke(dp(1), 0x1FFFFFFF)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        views.forEach { addView(it) }
    }

    private fun space(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun build() {
        gear = label("—", 36f, bold = true)
        advice = label("", 15f, bold = true)
        power = label("—", 32f, bold = true)
        torque = label("", 20f, SUB, bold = true)
        warmTitle = label("", 15f, bold = true)
        warmBar = Bar(ctx)
        warmSub = label("", 12f, SUB)
        coolant = Gauge("Eau")
        oil = Gauge("Huile (estimée)")
        boost = Gauge("Turbo")
        rpm = Gauge("Régime")
        status = label("", 11f, SUB)

        val gearRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gear)
            addView(advice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        }
        val powerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(power)
            addView(torque, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10); bottomMargin = dp(3) })
        }
        val warmCard = card(
            warmTitle,
            warmBar.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6); bottomMargin = dp(5) } },
            warmSub,
        )
        val f = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(card(label("Rapport", 11f, SUB), gearRow))
            addView(space(6))
            addView(card(label("Puissance · couple", 11f, SUB), powerRow))
            addView(space(6))
            addView(warmCard)
            // Les jauges se répartissent la hauteur restante (pas de grand vide en bas sur l'écran de 600 px).
            listOf(coolant, oil, boost, rpm).forEach { g ->
                addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
                addView(g.view)
            }
            addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
            addView(status)
        }
        miniDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SUB) }
        miniGear = label("—", 30f, bold = true)
        miniAdvice = label("", 19f, bold = true)
        miniPower = label("", 21f, bold = true)
        miniWater = label("", 19f, SUB)
        miniWarm = label("", 19f, BLUE, bold = true)
        val m = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(22), dp(10))
            fun gap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(14) }
            addView(View(ctx).apply { background = miniDot }, LinearLayout.LayoutParams(dp(13), dp(13)))
            addView(miniGear, gap())
            addView(miniAdvice, gap().apply { leftMargin = dp(8) })
            addView(miniPower, gap())
            addView(miniWater, gap())
            addView(miniWarm, gap())
        }
        // Verre sombre : assez opaque pour rester lisible par-dessus la carte de CarPlay.
        columnBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFC172242.toInt(), 0xFC0A0F1E.toInt()),
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0x40FFFFFF)
        }
        pillBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xF5172242.toInt(), 0xF50A0F1E.toInt()),
        ).apply {
            cornerRadius = dp(40).toFloat()
            setStroke(dp(1), 0x40FFFFFF)
        }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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

    /** Toucher : passe de la colonne complète à la pastille et inversement (choix gardé, comme dans les Réglages). */
    private fun toggle() {
        fr.p308.lanceur.UiPrefs.updatePerfCompact(!collapsed)
        applyCollapsed()
        root?.let { runCatching { wm.updateViewLayout(it, params()) } }
    }

    private var shownCompact: Boolean? = null

    private fun applyCollapsed() {
        shownCompact = collapsed
        full?.visibility = if (collapsed) View.GONE else View.VISIBLE
        mini?.visibility = if (collapsed) View.VISIBLE else View.GONE
        root?.background = if (collapsed) pillBg else columnBg
    }

    private fun params() = WindowManager.LayoutParams(
        if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else dp(224),
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

    /** Couleur d'une température d'eau ou d'huile : bleu froid, orange tiède, vert à bonne température, rouge trop chaud. */
    private fun tempColor(c: Float, hot: Float) = when {
        c >= hot -> RED
        c < 60f -> BLUE
        c < 75f -> ORANGE
        else -> GREEN
    }

    private fun minutes(m: Float) = if (m < 1f) "moins d'1 min" else "${ceil(m).toInt()} min"

    private fun render(e: EngineState) {
        val a = e.advice
        val w = Warmup.state.value
        val adviceColor = when (a.kind) {
            ShiftAdvice.Kind.UP -> GREEN
            ShiftAdvice.Kind.DOWN -> ORANGE
            ShiftAdvice.Kind.COLD -> BLUE
            ShiftAdvice.Kind.NONE -> TEXT
        }
        gear.text = a.gear?.let { ShiftAdvice.ordinal(it) } ?: "—"
        advice.text = a.text
        advice.setTextColor(adviceColor)

        power.text = "${e.powerCh ?: "—"} ch"
        torque.text = e.torqueNm?.let { "$it Nm" } ?: ""

        // Chauffe : temps restant (eau, puis huile qui chauffe plus lentement) avec la progression.
        val left = w.remainingMin
        val oilLeft = w.oilRemainingMin
        when {
            w.active && (left != null || oilLeft != null) -> {
                val total = w.elapsedS / 60f + (left ?: oilLeft ?: 0f)
                warmTitle.text = "Chauffe : encore ${minutes(left ?: oilLeft ?: 0f)}"
                warmTitle.setTextColor(BLUE)
                warmBar.fraction = if (total > 0f) w.elapsedS / 60f / total else 0f
                warmBar.color = BLUE
                warmSub.text = (oilLeft?.let { "Huile chaude dans ${minutes(it)}" } ?: "Pas de régime haut avant la fin")
            }
            (e.coolantC ?: 0) >= 75 && (w.oilC ?: 80f) >= 75f -> {
                warmTitle.text = "Moteur chaud ✓"
                warmTitle.setTextColor(GREEN)
                warmBar.fraction = 1f
                warmBar.color = GREEN
                warmSub.text = "Tu peux utiliser tout le régime"
            }
            else -> {
                warmTitle.text = "Chauffe"
                warmTitle.setTextColor(TEXT)
                warmBar.fraction = 0f
                warmSub.text = w.nextMin?.let { "Prochaine chauffe ≈ ${minutes(it)}" } ?: "En attente de l'OBD"
            }
        }

        val c = e.coolantC
        coolant.set(c?.let { "$it °C" } ?: "—", c?.let { (it - 20) / 90f } ?: 0f, c?.let { tempColor(it.toFloat(), 105f) } ?: ACCENT)
        val o = w.oilC
        oil.set(o?.let { "≈ ${it.roundToInt()} °C" } ?: "—", o?.let { (it - 20f) / 100f } ?: 0f, o?.let { tempColor(it, 130f) } ?: ACCENT)
        val b = e.boostBar
        boost.set(
            b?.let { "%.2f bar".format(Locale.FRANCE, it) } ?: "—",
            b?.let { it / 1.5f } ?: 0f,
            if ((b ?: 0f) >= 1.2f) ORANGE else ACCENT,
        )
        val r = e.rpm
        rpm.set(r?.let { "%,d tr/min".format(Locale.FRANCE, it).replace(' ', ' ') } ?: "—", r?.let { it / 5000f } ?: 0f, when {
            r == null -> ACCENT
            w.active && r > 3000 -> ORANGE
            r >= 4200 -> RED
            else -> ACCENT
        })

        status.text = (e.voltage?.let { "Batterie %.1f V  ·  ".format(Locale.FRANCE, it) } ?: "") +
            if (e.connected) "appui long : fermer" else e.status

        // Pastille : le point prend la couleur de l'eau (ou de l'huile si elle est plus froide).
        val coldest = listOfNotNull(c?.toFloat(), o).minOrNull()
        miniDot.setColor(
            when {
                c != null && c >= 105 -> RED
                coldest == null -> SUB
                else -> tempColor(coldest, 999f)
            }
        )
        miniGear.text = a.gear?.let { ShiftAdvice.ordinal(it) } ?: "—"
        miniAdvice.text = when (a.kind) {
            ShiftAdvice.Kind.UP -> "▲ ${ShiftAdvice.ordinal(a.target)}"
            ShiftAdvice.Kind.DOWN -> "▼ ${ShiftAdvice.ordinal(a.target)}"
            else -> ""
        }
        miniAdvice.setTextColor(adviceColor)
        miniAdvice.visibility = if (miniAdvice.text.isEmpty()) View.GONE else View.VISIBLE
        miniPower.text = "${e.powerCh ?: "—"} ch"
        miniWater.text = c?.let { "$it °C" } ?: ""
        miniWater.visibility = if (c == null) View.GONE else View.VISIBLE
        val warmMin = if (w.active) left ?: oilLeft else null
        miniWarm.text = warmMin?.let { "❄ ${ceil(it).toInt().coerceAtLeast(1)} min" } ?: ""
        miniWarm.visibility = if (warmMin == null) View.GONE else View.VISIBLE
    }

    private companion object {
        val TEXT = 0xFFEAF0FF.toInt()
        val SUB = 0xFF8C97B8.toInt()
        val ACCENT = 0xFF3D7BFF.toInt()
        val GREEN = 0xFF7CFC3A.toInt()
        val ORANGE = 0xFFFFA726.toInt()
        val RED = 0xFFFF4D4D.toInt()
        val BLUE = 0xFF64B5F6.toInt()
        val TRACK = 0x33FFFFFF
    }
}
