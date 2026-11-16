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
    /** v0.17 : l'eau a plafonné sous 80 °C en roulant (10 min sans gagner 2 °C). */
    val stalled: Boolean = false,
) {
    /**
     * Chauffe anormale, écartée des prévisions : eau qui plafonne, ou durée plus de 2,5 fois la normale (relevé du
     * 09/11 : 39,5 min depuis 43 °C, qui faisait prévoir plus de 40 min à chaque départ).
     */
    val abnormal: Boolean get() = stalled || minutes > 2.5f * Warmup.defaultMinutes(startC)
}

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
    /** v0.17 : l'eau plafonne à cette température en roulant (thermostat ?), sinon null. */
    val stalledC: Int? = null,
    /** v0.39 : température visée pour « moteur chaud » (80 °C, abaissée si le thermostat reste ouvert). */
    val targetC: Int = Warmup.TARGET_C,
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
    private var stallRefC = 0
    private var noRiseMovingS = 0.0
    private var stalled = false
    /** v0.39 : cible de la chauffe en cours (80 °C, ou moins si le thermostat est jugé ouvert). */
    private var target = TARGET_C
    /** v0.39 : thermostat déjà jugé pendant ce trajet ; plafond de l'eau sur ce trajet (null s'il n'y en a pas). */
    private var thermoDone = false
    private var tripStallC: Int? = null
    /** 10 min de roulage (30 km/h ou plus) sans que l'eau gagne 2 °C, sous 80 °C : plafond. */
    private const val STALL_S = 600.0

    /** Vrai en mode démo : rien n'est enregistré. */
    @Volatile
    var demo = false

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("warmup", Context.MODE_PRIVATE)
        prefs = p
        _history.value = decode(p.getString("list", null))
        Thermostat.init(p)
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
            noRiseMovingS = 0.0; stalled = false
            thermoDone = false; tripStallC = null
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
            stallRefC = coolant
            target = effectiveTarget()
        }
        val kw = (e.powerCh ?: 0) * 0.7355
        val o = (oil ?: coolant.toDouble())
        oil = (o + ((coolant - o) / TAU_S + POWER_GAIN * kw) * dt).coerceIn(-40.0, 140.0)

        val elapsedS = ((nowMs - startMs) / 1000).toInt()
        // v0.39 : surveillance du thermostat à chaque trajet, chauffe en cours ou non (avant, elle n'avait lieu que
        // pendant la chauffe : avec la cible abaissée, plus aucun résultat n'aurait été noté).
        if (!thermoDone) {
            if (coolant >= stallRefC + 2) { stallRefC = coolant; noRiseMovingS = 0.0 }
            else if (kmh >= 30f) noRiseMovingS += dt
            if (coolant >= TARGET_C) {
                thermoDone = true
                if (!demo) Thermostat.onWarmup(null, outsideC, nowMs)
            } else if (noRiseMovingS >= STALL_S && coolant >= 50) {
                thermoDone = true
                tripStallC = coolant
                ObdLog.log("CHAUFFE", "l'eau plafonne à $coolant °C après ${elapsedS / 60} min (10 min de roulage sans monter)")
                if (!demo) Thermostat.onWarmup(coolant, outsideC, nowMs)
            }
        }
        if (active) {
            if (kmh < 3f) idleS += dt.toInt().coerceAtLeast(1) else movingS += dt.toInt().coerceAtLeast(1)
            meters += kmh / 3.6 * dt
            if (coolMin == null && coolant >= target) coolMin = elapsedS / 60f
            if (oilMin == null && (oil ?: 0.0) >= target) oilMin = elapsedS / 60f
            // v0.39 : l'eau plafonne sous la cible : la chauffe s'arrête là (enregistrée comme anormale, écartée des
            // prévisions) au lieu de rester « en cours » tout le trajet.
            if (coolMin == null && tripStallC != null) {
                stalled = true
                coolMin = elapsedS / 60f
                finish()
            }
            if (active && coolMin != null && oilMin != null) finish()
        }
        if (++ticks % 30 == 0) saveOil(nowMs)
        _state.value = live(coolant, elapsedS)
    }

    private fun live(coolant: Int, elapsedS: Int): WarmState {
        val o = oil?.toFloat()
        if (!active) {
            return WarmState(oilC = o, coolantC = coolant, running = true, oilRemainingMin = oilRemaining(coolant, null),
                stalledC = tripStallC, targetC = target)
        }
        val (total, basis) = predict(startC)
        val elapsedMin = elapsedS / 60f
        val remaining = if (coolant >= target) 0f else {
            val planned = (total - elapsedMin).coerceAtLeast(0.5f)
            val gained = coolant - startC
            if (elapsedS >= 120 && gained >= 4) {
                val liveRate = gained / elapsedMin
                ((target - coolant) / liveRate + planned) / 2f
            } else planned
        }
        return WarmState(oilC = o, coolantC = coolant, running = true, active = true, startC = startC, elapsedS = elapsedS,
            remainingMin = remaining, oilRemainingMin = oilRemaining(coolant, remaining), basis = basis,
            stalledC = tripStallC, targetC = target)
    }

    /**
     * v0.39 : température visée pour dire « moteur chaud ». 80 °C normalement ; si le thermostat est jugé ouvert
     * (l'eau plafonne vers 75-77 °C), 3 °C sous ce plafond : sinon la chauffe ne se terminait jamais (relevés du 13
     * au 16/11) et aucune n'était enregistrée.
     */
    fun effectiveTarget(outcomes: List<Int?> = Thermostat.outcomes.value): Int =
        Thermostat.advise(outcomes)?.let { (it.typicalC - 3).coerceIn(60, TARGET_C) } ?: TARGET_C

    /** Minutes avant que l'huile atteigne 80 °C : retard appris après l'eau, ou vitesse actuelle du modèle. */
    private fun oilRemaining(coolant: Int, waterRemaining: Float?): Float? {
        val o = oil ?: return null
        if (o >= target) return 0f
        if (waterRemaining != null && waterRemaining > 0f) return waterRemaining + oilLag()
        val perMin = ((coolant - o) / TAU_S) * 60
        return if (perMin > 0.05) ((target - o) / perMin).toFloat().coerceAtMost(60f) else null
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
            if (movingS > 0) (meters / movingS * 3.6).roundToInt() else 0, stalled)
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
    fun predict(startC: Int, all: List<WarmRecord> = _history.value): Pair<Float, String> {
        val d = (TARGET_C - startC).coerceAtLeast(5).toFloat()
        val recs = all.filter { !it.abnormal }
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
        val ok = recs.filter { !it.abnormal }
        return norm(ok.filter { it.idlePct < 30 }) to norm(ok.filter { it.idlePct >= 40 })
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
            .put("ralenti", it.idlePct).put("kmh", it.avgKmh).put("plafond", it.stalled)
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
                    o.optInt("ralenti"), o.optInt("kmh"), o.optBoolean("plafond", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Avis sur le thermostat : null = rien à dire. */
data class ThermostatAdvice(val stalls: Int, val outOf: Int, val typicalC: Int, val text: String)

/**
 * v0.38 : thermostat resté ouvert. Un moteur chaud se tient vers 85-90 °C ; si l'eau plafonne sous 80 °C en roulant
 * (Warmup : 10 min à 30 km/h ou plus sans gagner 2 °C), le thermostat laisse sans doute passer l'eau vers le
 * radiateur trop tôt. Relevés du 13 au 15/11 : 71 à 75 °C à chaque trajet. Une chauffe isolée peut plafonner (froid
 * et petite vitesse) : l'avis demande 3 plafonds sur les 4 dernières chauffes suivies.
 * Chaque chauffe suivie donne un résultat : la température du plafond, ou null si l'eau a atteint 80 °C.
 */
object Thermostat {
    private const val KEEP = 6
    private var prefs: SharedPreferences? = null
    private val _outcomes = MutableStateFlow<List<Int?>>(emptyList())
    /** Dernières chauffes, la plus récente d'abord : plafond (°C) ou null (chauffe normale). */
    val outcomes: StateFlow<List<Int?>> = _outcomes.asStateFlow()

    fun init(p: SharedPreferences) {
        prefs = p
        _outcomes.value = decode(p.getString("thermo", null))
    }

    fun decode(s: String?): List<Int?> =
        s?.split(',')?.filter { it.isNotBlank() }?.map { it.trim().toIntOrNull() }?.take(KEEP) ?: emptyList()

    fun encode(list: List<Int?>): String = list.joinToString(",") { it?.toString() ?: "n" }

    @Synchronized
    fun onWarmup(stallC: Int?, outsideC: Float?, nowMs: Long) {
        val list = (listOf(stallC) + _outcomes.value).take(KEEP)
        _outcomes.value = list
        prefs?.edit()?.putString("thermo", encode(list))?.apply()
        advise(list)?.let { ObdLog.log("THERMOSTAT", it.text) }
    }

    /** Pur (tests) : avis à partir des dernières chauffes. */
    fun advise(list: List<Int?>): ThermostatAdvice? {
        val recent = list.take(4)
        val stalls = recent.filterNotNull()
        if (recent.size < 3 || stalls.size < 3) return null
        val typical = stalls.sorted()[stalls.size / 2]
        return ThermostatAdvice(stalls.size, recent.size, typical,
            "L'eau ne dépasse pas $typical °C en roulant (${stalls.size} trajets sur les ${recent.size} derniers), alors " +
                "qu'un moteur chaud tient 85 à 90 °C : le thermostat reste sans doute ouvert. Fais-le vérifier : un moteur " +
                "trop froid consomme plus et s'encrasse (vanne EGR, FAP).")
    }
}
