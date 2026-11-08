package fr.p308.lanceur.drive

import android.content.Context
import android.content.SharedPreferences
import fr.p308.lanceur.vehicle.EngineState
import fr.p308.lanceur.vehicle.ShiftAdvice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Un trajet terminé (ou en cours) avec ce qu'il faut pour calculer le score de conduite souple. */
data class TripRecord(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val movingS: Double,
    val maxKmh: Float,
    val peakCh: Int,
    /** Accélérations et freinages brusques. */
    val harshAccel: Int,
    val harshBrake: Int,
    /** Secondes de roulage avec l'OBD connecté, dont avec le conseil « passe la vitesse » affiché. */
    val obdS: Int,
    val overRevS: Int,
    /** Secondes à plus de 2 800 tr/min moteur froid (eau ou huile estimée sous 60 °C). */
    val coldRevS: Int,
    /** Montées de rapport repérées (-1 = trajet d'avant la v0.11, sans ce détail). */
    val shifts: Int = -1,
    /** Montées trop tard (au-delà de 2 900 tr/min sans accélérer fort, ou de 4 000 tr/min). */
    val lateShifts: Int = 0,
    /** Montées trop tôt (moteur sous 1 150 tr/min juste après). */
    val earlyShifts: Int = 0,
    val shiftRpmSum: Long = 0,
    /** Secondes où le moteur peine (conseil ▼ affiché plus de 2 s). */
    val lugS: Int = 0,
    /** Gazole estimé AVANT correction par les pleins (litres, OBD seulement). */
    val fuelRawL: Double = 0.0,
    /** Secondes à 70 km/h ou plus (voie rapide : le FAP peut se régénérer). -1 = trajet d'avant la v0.12. */
    val fastS: Int = -1,
) {
    /** Litres estimés, corrigés par les pleins (null sans OBD sur la majeure partie du trajet). */
    val fuelL: Double? get() = if (fuelRawL > 0.05 && obdS >= movingS * 0.6) fuelRawL * FuelModel.factor else null
    val l100: Double? get() = fuelL?.takeIf { distanceKm >= 1.0 }?.let { it / distanceKm * 100 }
    val avgKmh: Double get() = if (movingS > 1) distanceKm * 3600 / movingS else 0.0
    val overRevPct: Int get() = if (obdS > 0) (overRevS * 100f / obdS).roundToInt() else 0
    val lugPct: Int get() = if (obdS > 0) (lugS * 100f / obdS).roundToInt() else 0
    val goodShifts: Int get() = (shifts - lateShifts - earlyShifts).coerceAtLeast(0)
    val goodShiftPct: Int get() = if (shifts > 0) goodShifts * 100 / shifts else 0
    val avgShiftRpm: Int? get() = if (shifts > 0) (shiftRpmSum / shifts).toInt() else null
    val score: Int get() = TripLog.score(this)
}

/** Points retirés au score, par famille. */
data class ScorePenalties(val harsh: Int, val shifting: Int, val cold: Int)

/**
 * Historique des trajets. Un trajet commence dès 5 km/h et se termine après 5 minutes à l'arrêt (ou quand
 * l'autoradio se réveille après une pause plus longue). Gardé s'il fait au moins 500 m ; 100 trajets au plus.
 * Le trajet en cours est sauvegardé toutes les 30 s pour survivre à un arrêt de l'appli.
 */
object TripLog {
    private const val MAX_TRIPS = 100
    private const val END_AFTER_MS = 5 * 60_000L
    private const val MIN_KM = 0.5
    /** Seuils en km/h par seconde : ≈ 0,3 g en accélération, 0,37 g au freinage. Au-delà de 45 : saut de mesure. */
    private const val HARSH_ACCEL = 11f
    private const val HARSH_BRAKE = -13f
    private const val JUMP = 45f

    private var prefs: SharedPreferences? = null
    private val _history = MutableStateFlow<List<TripRecord>>(emptyList())
    val history: StateFlow<List<TripRecord>> = _history.asStateFlow()
    private val _current = MutableStateFlow<TripRecord?>(null)
    val current: StateFlow<TripRecord?> = _current.asStateFlow()

    /** Vrai en mode démo : rien n'est enregistré. */
    @Volatile
    var demo = false

    private var lastMoveMs = 0L
    private var lastFeedNanos = 0L
    private var lastKmh = 0f
    private var refKmh = 0f
    private var refNanos = 0L
    private var inAccel = false
    private var inBrake = false
    private var ticks = 0

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("trips", Context.MODE_PRIVATE)
        prefs = p
        _history.value = p.getString("list", null)?.let(::decodeList) ?: emptyList()
        // Trajet resté ouvert (appli arrêtée) : terminé s'il est ancien, repris sinon.
        p.getString("current", null)?.let(::decodeOne)?.let { cur ->
            if (System.currentTimeMillis() - cur.endMs > END_AFTER_MS) close(cur) else {
                _current.value = cur
                lastMoveMs = cur.endMs
            }
        }
    }

    /** Score sur 100 : brusqueries (40 pts), passages de vitesse (30), moteur froid poussé (20). */
    fun score(t: TripRecord): Int {
        val p = penalties(t)
        return (100 - p.harsh - p.shifting - p.cold).coerceIn(0, 100)
    }

    fun penalties(t: TripRecord): ScorePenalties {
        // Au moins 5 km pour la moyenne : un seul freinage sur un trajet très court ne doit pas tout faire chuter.
        val km = max(t.distanceKm, 5.0)
        val harshPer10 = (t.harshAccel + t.harshBrake) * 10 / km
        val penHarsh = minOf(40.0, harshPer10 * 5)
        val penShift = when {
            t.obdS < 60 -> 0.0
            t.shifts < 0 -> minOf(30.0, t.overRevS * 100.0 / t.obdS * 0.6) // ancien calcul (trajets d'avant v0.11)
            else -> {
                // Montées mal placées (comptées dès 5 montées), régime trop haut malgré ▲, moteur qui peine (▼).
                val badPct = if (t.shifts >= 5) (t.lateShifts + t.earlyShifts) * 100.0 / t.shifts else 0.0
                minOf(30.0, 0.25 * badPct + 0.4 * t.overRevPct + 0.5 * t.lugPct)
            }
        }
        val penCold = minOf(20.0, t.coldRevS / 3.0)
        return ScorePenalties(penHarsh.roundToInt(), penShift.roundToInt(), penCold.roundToInt())
    }

    /** Vitesse (OBD ou GPS), appelée par [Speed.report]. */
    @Synchronized
    fun feed(kmh: Float, nanos: Long, nowMs: Long = System.currentTimeMillis()) {
        var t = _current.value
        if (t == null) {
            if (kmh < 5f) {
                lastFeedNanos = nanos
                lastKmh = kmh
                return
            }
            t = TripRecord(nowMs, nowMs, 0.0, 0.0, 0f, 0, 0, 0, 0, 0, 0, shifts = 0, fastS = 0)
            refNanos = 0L
            inAccel = false
            inBrake = false
        }
        val dt = if (lastFeedNanos == 0L) 0.0 else ((nanos - lastFeedNanos) / 1e9).coerceIn(0.0, 5.0)
        val avg = (kmh + lastKmh) / 2.0
        lastFeedNanos = nanos
        lastKmh = kmh
        if (kmh > 2f) lastMoveMs = nowMs

        var accel = t.harshAccel
        var brake = t.harshBrake
        if (refNanos == 0L) {
            refKmh = kmh; refNanos = nanos
        } else {
            val span = (nanos - refNanos) / 1e9f
            if (span >= 1f) {
                val a = (kmh - refKmh) / span
                if (span <= 3f && abs(a) < JUMP) {
                    if (a > HARSH_ACCEL) { if (!inAccel) accel++; inAccel = true } else if (a < HARSH_ACCEL / 2) inAccel = false
                    if (a < HARSH_BRAKE) { if (!inBrake) brake++; inBrake = true } else if (a > HARSH_BRAKE / 2) inBrake = false
                }
                refKmh = kmh; refNanos = nanos
            }
        }
        _current.value = t.copy(
            endMs = if (kmh > 2f) nowMs else t.endMs,
            distanceKm = t.distanceKm + avg / 3.6 * dt / 1000,
            movingS = t.movingS + if (avg > 2) dt else 0.0,
            maxKmh = max(t.maxKmh, kmh),
            harshAccel = accel, harshBrake = brake,
        )
    }

    /** Une fois par seconde (service) : régime, conseil de passage, moteur froid, fin du trajet. */
    @Synchronized
    fun tick(e: EngineState, nowMs: Long = System.currentTimeMillis()) {
        val t = _current.value ?: return
        if (lastMoveMs == 0L) lastMoveMs = t.endMs
        if (nowMs - lastMoveMs > END_AFTER_MS) {
            close(t)
            return
        }
        val moving = lastKmh > 5f
        // Gazole (moteur tournant, même à l'arrêt) et temps sur voie rapide, avant le détail OBD ci-dessous.
        val engineOn = e.connected && (e.rpm ?: 0) > 400
        val base = t.copy(
            fuelRawL = t.fuelRawL + if (engineOn) FuelModel.litersPerHour(e.powerCh) / 3600.0 else 0.0,
            fastS = t.fastS.coerceAtLeast(0) + if (lastKmh >= 70f) 1 else 0,
        )
        _current.value = if (moving && e.connected && e.rpm != null) {
            val t = base
            val kind = e.advice.kind
            // Le conseil ne compte qu'après un délai : le temps de débrayer et de passer la vitesse.
            if (kind == ShiftAdvice.Kind.UP) { if (upSinceMs == 0L) upSinceMs = nowMs } else upSinceMs = 0L
            if (kind == ShiftAdvice.Kind.DOWN) { if (downSinceMs == 0L) downSinceMs = nowMs } else downSinceMs = 0L
            val shift = detectShift(e, nowMs)
            val cold = fr.p308.lanceur.vehicle.Warmup.coldTemp(e.coolantC) ?: 90
            t.copy(
                obdS = t.obdS + 1,
                overRevS = t.overRevS + if (upSinceMs != 0L && nowMs - upSinceMs >= 3_000L) 1 else 0,
                lugS = t.lugS + if (downSinceMs != 0L && nowMs - downSinceMs >= 2_000L) 1 else 0,
                coldRevS = t.coldRevS + if (cold < 60 && e.rpm > 2800) 1 else 0,
                peakCh = max(t.peakCh, e.powerCh ?: 0),
                shifts = t.shifts.coerceAtLeast(0) + if (shift != null) 1 else 0,
                lateShifts = t.lateShifts + if (shift == ShiftKind.LATE) 1 else 0,
                earlyShifts = t.earlyShifts + if (shift == ShiftKind.EARLY) 1 else 0,
                shiftRpmSum = t.shiftRpmSum + if (shift != null) lastShiftRpm.toLong() else 0L,
            )
        } else {
            upSinceMs = 0L
            downSinceMs = 0L
            base
        }
        if (++ticks % 30 == 0) saveCurrent()
    }

    private enum class ShiftKind { GOOD, LATE, EARLY }

    private var upSinceMs = 0L
    private var downSinceMs = 0L
    private var gearSeen: Int? = null
    private var gearRpm = 0
    private var gearLoad = 0
    private var gearMs = 0L
    private var lastShiftRpm = 0

    /**
     * Montée de rapport : le rapport reconnu passe de g à g+1 (ou plus) en moins de 4 s (embrayage). Le régime
     * du passage est le dernier relevé sur l'ancien rapport. Bien placée : 1 500 à 2 900 tr/min en conduite normale,
     * jusqu'à 4 000 tr/min en accélération franche (charge ≥ 70 %), et moteur au-dessus de 1 150 tr/min après.
     */
    private fun detectShift(e: EngineState, nowMs: Long): ShiftKind? {
        val g = e.advice.gear ?: return null
        val rpm = e.rpm ?: return null
        val prev = gearSeen
        var kind: ShiftKind? = null
        if (prev != null && g > prev && nowMs - gearMs <= 4_000L) {
            lastShiftRpm = gearRpm
            kind = when {
                rpm < 1150 -> ShiftKind.EARLY
                gearRpm > 4000 || (gearRpm > 2900 && gearLoad < 70) -> ShiftKind.LATE
                else -> ShiftKind.GOOD
            }
        }
        gearSeen = g
        gearRpm = rpm
        gearLoad = e.loadPct ?: 50
        gearMs = nowMs
        return kind
    }

    /** Bouton « Terminer le trajet ». */
    @Synchronized
    fun finishNow() {
        _current.value?.let(::close)
    }

    /** Passage en mode démo ou retour : le trajet en cours est abandonné (données de démo jamais gardées). */
    @Synchronized
    fun discard() {
        _current.value = null
        lastMoveMs = 0L
        prefs?.edit()?.remove("current")?.apply()
    }

    @Synchronized
    fun delete(startMs: Long) {
        _history.value = _history.value.filterNot { it.startMs == startMs }
        prefs?.edit()?.putString("list", encodeList(_history.value))?.apply()
    }

    private fun close(t: TripRecord) {
        _current.value = null
        lastMoveMs = 0L
        prefs?.edit()?.remove("current")?.apply()
        if (demo || t.distanceKm < MIN_KM) return
        _history.value = (listOf(t) + _history.value).take(MAX_TRIPS)
        prefs?.edit()?.putString("list", encodeList(_history.value))?.apply()
    }

    private fun saveCurrent() {
        if (demo) return
        _current.value?.let { prefs?.edit()?.putString("current", encode(it).toString())?.apply() }
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<TripRecord> {
        val h = 3_600_000L
        fun trip(ago: Long, km: Double, min: Int, maxK: Float, ch: Int, acc: Int, brk: Int, revPct: Int, cold: Int): TripRecord {
            val obd = min * 60
            val shifts = (km * 2.2).toInt() + 4
            val late = shifts * revPct / 40
            return TripRecord(now - ago, now - ago + min * 60_000L, km, min * 60.0 * 0.85, maxK, ch, acc, brk, obd, obd * revPct / 300, cold,
                shifts, late, if (revPct > 10) 2 else 0, shifts * (2200L + revPct * 25L), obd * (revPct / 4) / 100,
                km * (0.052 + revPct / 2000.0), if (maxK > 100f) min * 60 / 3 else 0)
        }
        return listOf(
            trip(2 * h, 18.4, 24, 112f, 88, 1, 0, 6, 0),
            trip(9 * h, 4.2, 11, 58f, 61, 2, 1, 22, 45),
            trip(26 * h, 18.1, 23, 118f, 92, 0, 1, 4, 0),
            trip(33 * h, 62.7, 48, 131f, 95, 3, 2, 12, 12),
            trip(50 * h, 7.9, 15, 74f, 70, 0, 0, 9, 0),
        )
    }

    private fun encode(t: TripRecord) = JSONObject()
        .put("s", t.startMs).put("e", t.endMs).put("km", t.distanceKm).put("mv", t.movingS)
        .put("max", t.maxKmh.toDouble()).put("ch", t.peakCh).put("acc", t.harshAccel).put("frein", t.harshBrake)
        .put("obd", t.obdS).put("haut", t.overRevS).put("froid", t.coldRevS)
        .put("passages", t.shifts).put("tard", t.lateShifts).put("tot", t.earlyShifts).put("rpmPassages", t.shiftRpmSum)
        .put("peine", t.lugS).put("gazole", t.fuelRawL).put("rapide", t.fastS)

    private fun decode(o: JSONObject) = TripRecord(
        o.getLong("s"), o.getLong("e"), o.getDouble("km"), o.getDouble("mv"), o.getDouble("max").toFloat(),
        o.optInt("ch"), o.optInt("acc"), o.optInt("frein"), o.optInt("obd"), o.optInt("haut"), o.optInt("froid"),
        o.optInt("passages", -1), o.optInt("tard"), o.optInt("tot"), o.optLong("rpmPassages"), o.optInt("peine"),
        o.optDouble("gazole", 0.0), o.optInt("rapide", -1),
    )

    private fun encodeList(list: List<TripRecord>) = JSONArray(list.map(::encode)).toString()

    private fun decodeList(s: String): List<TripRecord> =
        runCatching { JSONArray(s).let { a -> List(a.length()) { decode(a.getJSONObject(it)) } } }.getOrDefault(emptyList())

    private fun decodeOne(s: String): TripRecord? = runCatching { decode(JSONObject(s)) }.getOrNull()
}
