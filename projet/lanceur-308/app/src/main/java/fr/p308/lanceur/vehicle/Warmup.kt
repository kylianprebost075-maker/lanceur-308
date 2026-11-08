package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToInt

/** Une montée en température enregistrée : de la température de l'eau au démarrage jusqu'à 80 °C. */
data class WarmRecord(
    val startMs: Long,
    val startC: Int,
    val outsideC: Float?,
    /** Minutes pour que l'eau atteigne 80 °C. */
    val minutes: Float,
    /** Minutes pour que l'huile (estimée) atteigne 80 °C, si le moteur a tourné assez longtemps. */
    val oilMinutes: Float?,
    /** Part du temps à l'arrêt (ralenti) pendant la chauffe. */
    val idlePct: Int,
    val avgKmh: Int,
)

data class WarmState(
    /** Huile estimée (°C), null tant que l'eau n'a pas été lue. */
    val oilC: Float? = null,
    val coolantC: Int? = null,
    val running: Boolean = false,
    /** Chauffe en cours (démarrage avec l'eau sous 60 °C). */
    val active: Boolean = false,
    val startC: Int? = null,
    val elapsedS: Int = 0,
    val remainingMin: Float? = null,
    val oilRemainingMin: Float? = null,
    /** Moteur coupé : durée prévue pour la prochaine chauffe. */
    val nextMin: Float? = null,
    val nextFromC: Int? = null,
    val basis: String = "",
)

/**
 * Température d'huile ESTIMÉE et temps de chauffe. La 308 (EDC16C34) ne donne pas la température d'huile sur
 * l'OBD (PID 5C absent, séance du 08/11). Modèle : l'huile suit l'eau avec retard (constante 8 min) et chauffe
 * un peu plus fort qu'elle quand le moteur fournit de la puissance (≈ +10 °C sur autoroute) ; moteur coupé, elle
 * refroidit vers la température extérieure (constante 90 min).
 * Chaque chauffe complète est enregistrée (eau de départ, minutes, part de ralenti) : la durée de la prochaine est
 * prévue d'après les chauffes passées, à température de départ comparable.
 */
object Warmup {
    const val TARGET_C = 80
    private const val TAU_S = 480.0
    private const val POWER_GAIN = 0.0008 // °C par seconde et par kW
    private const val TAU_COOL_S = 5400.0
    private const val MAX_RECORDS = 60

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(WarmState())
    val state: StateFlow<WarmState> = _state.asStateFlow()
    private val _history = MutableStateFlow<List<WarmRecord>>(emptyList())
    val history: StateFlow<List<WarmRecord>> = _history.asStateFlow()

    @Volatile
    private var oil: Double? = null
    private var wasRunning = false
    private var pendingStart = false
    private var lastMs = 0L
    private var ticks = 0

    // Chauffe en cours
    private var active = false
    private var startMs = 0L
    private var startC = 0
    private var startOutside: Float? = null
    private var idleS = 0
    private var movingS = 0
    private var meters = 0.0
    private var coolMin: Float? = null
    private var oilMin: Float? = null

    /** Vrai en mode démo : rien n'est enregistré. */
    @Volatile
    var demo = false

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("warmup", Context.MODE_PRIVATE)
        prefs = p
        _history.value = decode(p.getString("list", null))
    }

    /** Température qui décide du « moteur froid » : la plus basse de l'eau et de l'huile estimée. */
    fun coldTemp(coolantC: Int?): Int? = listOfNotNull(coolantC, oil?.roundToInt()).minOrNull()

    /** Une fois par seconde (service). [kmh] = vitesse actuelle, [outsideC] = extérieur s'il est connu. */
    @Synchronized
    fun tick(e: EngineState, kmh: Float, outsideC: Float?, nowMs: Long = System.currentTimeMillis()) {
        val dt = if (lastMs == 0L) 1.0 else ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
        lastMs = nowMs
        val running = e.connected && (e.rpm ?: 0) > 400
        if (!running) {
            if (wasRunning) stop(nowMs)
            wasRunning = false
            oil?.let { o ->
                val amb = (outsideC?.toDouble() ?: e.coolantC?.toDouble() ?: 15.0).coerceAtMost(o)
                oil = amb + (o - amb) * exp(-dt / TAU_COOL_S)
            }
            val from = e.coolantC ?: outsideC?.roundToInt()
            val pred = from?.takeIf { it < 60 }?.let { predict(it) }
            _state.value = WarmState(oilC = oil?.toFloat(), coolantC = e.coolantC, nextMin = pred?.first, nextFromC = from,
                basis = pred?.second ?: "")
            return
        }
        if (!wasRunning) {
            pendingStart = true
            idleS = 0; movingS = 0; meters = 0.0; coolMin = null; oilMin = null; active = false
        }
        wasRunning = true
        val coolant = e.coolantC
        if (coolant == null) {
            _state.value = _state.value.copy(running = true)
            return
        }
        if (pendingStart) {
            pendingStart = false
            oil = initialOil(coolant, outsideC, nowMs)
            active = coolant < 60
            startMs = nowMs
            startC = coolant
            startOutside = outsideC
        }
        val kw = (e.powerCh ?: 0) * 0.7355
        val o = (oil ?: coolant.toDouble())
        oil = (o + ((coolant - o) / TAU_S + POWER_GAIN * kw) * dt).coerceIn(-40.0, 140.0)

        val elapsedS = ((nowMs - startMs) / 1000).toInt()
        if (active) {
            if (kmh < 3f) idleS += dt.toInt().coerceAtLeast(1) else movingS += dt.toInt().coerceAtLeast(1)
            meters += kmh / 3.6 * dt
            if (coolMin == null && coolant >= TARGET_C) coolMin = elapsedS / 60f
            if (oilMin == null && (oil ?: 0.0) >= TARGET_C) oilMin = elapsedS / 60f
            if (coolMin != null && oilMin != null) finish()
        }
        if (++ticks % 30 == 0) saveOil(nowMs)
        _state.value = live(coolant, elapsedS)
    }

    private fun live(coolant: Int, elapsedS: Int): WarmState {
        val o = oil?.toFloat()
        if (!active) {
            return WarmState(oilC = o, coolantC = coolant, running = true, oilRemainingMin = oilRemaining(coolant, null))
        }
        val (total, basis) = predict(startC)
        val elapsedMin = elapsedS / 60f
        val remaining = if (coolant >= TARGET_C) 0f else {
            val planned = (total - elapsedMin).coerceAtLeast(0.5f)
            val gained = coolant - startC
            if (elapsedS >= 120 && gained >= 4) {
                val liveRate = gained / elapsedMin
                ((TARGET_C - coolant) / liveRate + planned) / 2f
            } else planned
        }
        return WarmState(oilC = o, coolantC = coolant, running = true, active = true, startC = startC, elapsedS = elapsedS,
            remainingMin = remaining, oilRemainingMin = oilRemaining(coolant, remaining), basis = basis)
    }

    /** Minutes avant que l'huile atteigne 80 °C : retard appris après l'eau, ou vitesse actuelle du modèle. */
    private fun oilRemaining(coolant: Int, waterRemaining: Float?): Float? {
        val o = oil ?: return null
        if (o >= TARGET_C) return 0f
        if (waterRemaining != null && waterRemaining > 0f) return waterRemaining + oilLag()
        val perMin = ((coolant - o) / TAU_S) * 60
        return if (perMin > 0.05) ((TARGET_C - o) / perMin).toFloat().coerceAtMost(60f) else null
    }

    private fun oilLag(): Float =
        _history.value.mapNotNull { r -> r.oilMinutes?.let { it - r.minutes } }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 5f

    private fun initialOil(coolant: Int, outsideC: Float?, nowMs: Long): Double {
        val p = prefs ?: return coolant.toDouble()
        val saved = p.getFloat("oil", Float.NaN)
        val at = p.getLong("oilAt", 0L)
        val age = (nowMs - at) / 1000.0
        if (saved.isNaN() || age < 0 || age > 12 * 3600) return coolant.toDouble()
        val amb = (outsideC?.toDouble() ?: coolant.toDouble()).coerceAtMost(saved.toDouble())
        val decayed = amb + (saved - amb) * exp(-age / TAU_COOL_S)
        return decayed.coerceIn(coolant - 5.0, coolant + 20.0)
    }

    private fun saveOil(nowMs: Long) {
        if (demo) return
        oil?.let { prefs?.edit()?.putFloat("oil", it.toFloat())?.putLong("oilAt", nowMs)?.apply() }
    }

    private fun stop(nowMs: Long) {
        saveOil(nowMs)
        if (active && coolMin != null) finish()
        active = false
    }

    /** Enregistre la chauffe en cours (eau à 80 °C atteinte). */
    private fun finish() {
        val m = coolMin ?: return
        active = false
        if (demo) return
        val total = (idleS + movingS).coerceAtLeast(1)
        val r = WarmRecord(startMs, startC, startOutside, m, oilMin, idleS * 100 / total,
            if (movingS > 0) (meters / movingS * 3.6).roundToInt() else 0)
        val list = (listOf(r) + _history.value).take(MAX_RECORDS)
        _history.value = list
        prefs?.edit()?.putString("list", encode(list))?.apply()
    }

    /** Valeurs de départ (avant toute chauffe enregistrée) : diesel 1.6 HDi, roulage mixte. */
    fun defaultMinutes(startC: Int): Float = 2f + 0.16f * (TARGET_C - startC).coerceAtLeast(5)

    /**
     * Durée prévue (minutes jusqu'à 80 °C) pour une eau de départ à [startC], et sur quoi elle repose.
     * 4 chauffes ou plus avec des températures de départ assez différentes : droite des moindres carrés ;
     * sinon les valeurs de départ corrigées par l'écart médian observé.
     */
    fun predict(startC: Int, recs: List<WarmRecord> = _history.value): Pair<Float, String> {
        val d = (TARGET_C - startC).coerceAtLeast(5).toFloat()
        if (recs.size >= 4) {
            val xs = recs.map { (TARGET_C - it.startC).coerceAtLeast(5).toFloat() }
            val ys = recs.map { it.minutes }
            if (xs.max() - xs.min() >= 8f) {
                val mx = xs.average().toFloat()
                val my = ys.average().toFloat()
                val sxx = xs.sumOf { ((it - mx) * (it - mx)).toDouble() }.toFloat()
                val sxy = xs.indices.sumOf { ((xs[it] - mx) * (ys[it] - my)).toDouble() }.toFloat()
                val b = sxy / sxx
                if (b > 0f) return (my + b * (d - mx)).coerceIn(2f, 45f) to "d'après tes ${recs.size} chauffes"
            }
        }
        if (recs.isNotEmpty()) {
            val ratios = recs.map { it.minutes / defaultMinutes(it.startC) }.sorted()
            val med = ratios[ratios.size / 2]
            return (defaultMinutes(startC) * med).coerceIn(2f, 45f) to
                "d'après ${if (recs.size == 1) "ta chauffe" else "tes ${recs.size} chauffes"} enregistrée${if (recs.size > 1) "s" else ""}"
        }
        return defaultMinutes(startC) to "estimation de départ (aucune chauffe enregistrée)"
    }

    /**
     * Comparaison des façons de chauffer : durée ramenée à un départ à 10 °C, en roulant tout de suite
     * (moins de 30 % de ralenti) ou en laissant tourner d'abord (40 % de ralenti ou plus). Null s'il manque des données.
     */
    fun styles(recs: List<WarmRecord>): Pair<Pair<Float, Int>?, Pair<Float, Int>?> {
        fun norm(list: List<WarmRecord>) = list.takeIf { it.isNotEmpty() }?.let { l ->
            (l.map { it.minutes / defaultMinutes(it.startC) }.average().toFloat() * defaultMinutes(10)) to l.size
        }
        return norm(recs.filter { it.idlePct < 30 }) to norm(recs.filter { it.idlePct >= 40 })
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<WarmRecord> {
        val day = 86_400_000L
        return listOf(
            WarmRecord(now - day, 6, 5f, 12.5f, 17.0f, 8, 38),
            WarmRecord(now - 2 * day, 9, 8f, 11.0f, 16.0f, 12, 42),
            WarmRecord(now - 3 * day, 4, 3f, 19.5f, 26.0f, 55, 21),
            WarmRecord(now - 5 * day, 14, 13f, 10.0f, 14.5f, 10, 45),
            WarmRecord(now - 6 * day, 7, 6f, 17.0f, 23.0f, 48, 24),
        )
    }

    private fun encode(list: List<WarmRecord>) = JSONArray(list.map {
        JSONObject().put("t", it.startMs).put("c", it.startC).put("ext", it.outsideC?.toDouble() ?: -999.0)
            .put("min", it.minutes.toDouble()).put("huile", it.oilMinutes?.toDouble() ?: -1.0)
            .put("ralenti", it.idlePct).put("kmh", it.avgKmh)
    }).toString()

    private fun decode(s: String?): List<WarmRecord> {
        if (s == null) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                WarmRecord(
                    o.getLong("t"), o.getInt("c"), o.optDouble("ext", -999.0).toFloat().takeIf { it > -900f },
                    o.getDouble("min").toFloat(), o.optDouble("huile", -1.0).toFloat().takeIf { it >= 0f },
                    o.optInt("ralenti"), o.optInt("kmh"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
