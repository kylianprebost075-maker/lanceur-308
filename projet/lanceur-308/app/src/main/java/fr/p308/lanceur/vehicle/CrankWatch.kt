package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class CrankVerdict(val label: String) { GOOD("Bonne"), FAIR("À surveiller"), WEAK("Faible") }

/**
 * Un démarrage mesuré : tension au repos (contact mis), tension la plus basse au démarreur (null si la chute
 * n'a pas pu être saisie, par exemple connexion coupée pendant le démarreur) et tension de charge juste après
 * (alternateur).
 */
data class CrankRecord(
    val atMs: Long,
    val restV: Float,
    val minV: Float?,
    val coolantC: Int?,
    val chargeV: Float? = null,
) {
    val verdict: CrankVerdict get() = CrankWatch.judge(restV, minV)
}

/**
 * Santé de la batterie au démarrage. Moteur coupé, la boucle OBD lit la tension (ATRV du vLinker) plusieurs fois
 * par seconde ; quand le régime passe au-dessus de 400 tr/min, on garde la tension au repos d'avant et la plus
 * basse des 10 dernières secondes (chute au démarreur).
 *
 * v0.11 : le démarreur fait souvent chuter l'alimentation de l'autoradio et couper le Bluetooth ; la connexion
 * revient moteur tournant. Les relevés et l'état « moteur coupé » sont donc gardés d'une connexion à l'autre
 * (3 min), et si la chute n'a pas été saisie, le démarrage est quand même noté avec la tension au repos et la
 * tension de charge.
 */
object CrankWatch {
    private const val MAX_RECORDS = 40
    private const val CRANK_WINDOW_MS = 10_000L
    private const val KEEP_MS = 60_000L
    /** Au-delà, la coupure entre deux connexions est trop longue pour relier « avant » et « après ». */
    private const val SESSION_GAP_MS = 3 * 60_000L
    /** Au démarreur d'un diesel la tension descend toujours sous 11 V : au-dessus, la chute n'a pas été vue. */
    private const val CRANK_SEEN_BELOW = 11.0f

    private var prefs: SharedPreferences? = null
    private val _history = MutableStateFlow<List<CrankRecord>>(emptyList())

    /** Démarrages mesurés, le plus récent d'abord. */
    val history: StateFlow<List<CrankRecord>> = _history.asStateFlow()

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var wasRunning: Boolean? = null
    private var lastSeenMs = 0L

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)
        prefs = p
        _history.value = decode(p.getString("starts", null))
    }

    /** Seuils classiques d'un test au démarreur (9,6 V mini) avec une marge « à surveiller ». */
    fun judge(restV: Float, minV: Float?): CrankVerdict = when {
        (minV != null && minV < 9.6f) || restV < 12.0f -> CrankVerdict.WEAK
        (minV != null && minV < 10.2f) || restV < 12.3f -> CrankVerdict.FAIR
        else -> CrankVerdict.GOOD
    }

    /** Nouvelle connexion OBD : l'état n'est oublié que si la coupure a été longue. */
    @Synchronized
    fun resetSession(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastSeenMs > SESSION_GAP_MS) {
            wasRunning = null
            samples.clear()
        }
    }

    /** Vrai si un démarrage maintenant serait mesuré (moteur coupé, tension lue il y a moins de 3 s). */
    @Synchronized
    fun ready(nowMs: Long = System.currentTimeMillis()): Boolean =
        wasRunning == false && samples.isNotEmpty() && nowMs - samples.last().first < 3_000L

    /**
     * À appeler à chaque tour de la boucle OBD. [volts] peut être nul (pas lu à ce tour), [rpm] nul si le
     * calculateur n'a pas répondu (fréquent pendant le démarreur). Renvoie le démarrage s'il vient d'être noté.
     */
    @Synchronized
    fun onSample(volts: Float?, rpm: Int?, coolantC: Int?, nowMs: Long = System.currentTimeMillis()): CrankRecord? {
        lastSeenMs = nowMs
        volts?.let { samples.addLast(nowMs to it) }
        while (samples.isNotEmpty() && nowMs - samples.first().first > KEEP_MS) samples.removeFirst()
        if (rpm == null) return null
        val running = rpm > 400
        val before = wasRunning
        wasRunning = running
        if (before != false || !running) return null
        if (samples.isEmpty()) return null // rien lu moteur coupé : rien à dire

        val rest = samples.maxOf { it.second }
        val low = samples.filter { nowMs - it.first <= CRANK_WINDOW_MS }.minOfOrNull { it.second }
        val minV = low?.takeIf { it < CRANK_SEEN_BELOW && it < rest - 0.5f }
        samples.clear()
        val record = CrankRecord(nowMs, rest, minV, coolantC)
        store((listOf(record) + _history.value).take(MAX_RECORDS))
        return record
    }

    /** Tension moteur tournant : les 3 premières minutes après un démarrage donnent la tension de charge. */
    @Synchronized
    fun onRunningVolts(volts: Float?, nowMs: Long = System.currentTimeMillis()) {
        if (volts == null) return
        val list = _history.value
        val last = list.firstOrNull() ?: return
        if (nowMs - last.atMs > 3 * 60_000L || nowMs < last.atMs) return
        if (last.chargeV != null && last.chargeV >= volts) return
        store(listOf(last.copy(chargeV = volts)) + list.drop(1))
    }

    private fun store(list: List<CrankRecord>) {
        _history.value = list
        prefs?.edit()?.putString("starts", encode(list))?.apply()
    }

    /** Écart de la tension mini entre les 5 derniers démarrages mesurés et les 5 d'avant (null s'il en manque). */
    fun trend(list: List<CrankRecord>): Float? {
        val measured = list.mapNotNull { it.minV }
        if (measured.size < 10) return null
        return measured.take(5).average().toFloat() - measured.drop(5).take(5).average().toFloat()
    }

    /** Charge de l'alternateur : 13,6 à 14,8 V attendu moteur tournant. */
    fun chargeText(v: Float): String = when {
        v < 13.3f -> "charge faible : alternateur à contrôler"
        v > 15.0f -> "charge trop forte : régulateur à contrôler"
        else -> "alternateur OK"
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<CrankRecord> {
        val day = 86_400_000L
        val mins = listOf(10.6f, null, 10.6f, 10.4f, 10.5f, 10.3f, 10.4f, 10.2f, 10.3f, 10.1f)
        return mins.mapIndexed { i, m ->
            CrankRecord(now - i * day - 7 * 3_600_000L, 12.55f - i * 0.02f, m, if (i % 3 == 0) 6 else 45, 14.2f + (i % 3) * 0.1f)
        }
    }

    private fun encode(list: List<CrankRecord>) = JSONArray(list.map {
        JSONObject().put("t", it.atMs).put("rest", it.restV.toDouble()).put("min", it.minV?.toDouble() ?: -1.0)
            .put("eau", it.coolantC ?: -999).put("charge", it.chargeV?.toDouble() ?: -1.0)
    }).toString()

    private fun decode(s: String?): List<CrankRecord> {
        if (s == null) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CrankRecord(
                    o.getLong("t"), o.getDouble("rest").toFloat(),
                    o.optDouble("min", -1.0).toFloat().takeIf { it > 0f },
                    o.optInt("eau", -999).takeIf { it != -999 },
                    o.optDouble("charge", -1.0).toFloat().takeIf { it > 0f },
                )
            }
        }.getOrDefault(emptyList())
    }
}
