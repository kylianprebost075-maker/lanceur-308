package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class CrankVerdict(val label: String) { GOOD("Bonne"), FAIR("À surveiller"), WEAK("Faible") }

/** Un démarrage mesuré : tension au repos (contact mis) et tension la plus basse pendant le démarreur. */
data class CrankRecord(val atMs: Long, val restV: Float, val minV: Float, val coolantC: Int?) {
    val verdict: CrankVerdict get() = CrankWatch.judge(restV, minV)
}

/**
 * Santé de la batterie au démarrage. Moteur coupé, la boucle OBD lit la tension (ATRV du vLinker) plusieurs fois
 * par seconde ; quand le régime passe au-dessus de 400 tr/min, on garde la tension au repos d'avant et la plus
 * basse des 6 dernières secondes (chute au démarreur). Une batterie fatiguée chute de plus en plus bas.
 * Il faut que le boîtier soit connecté AVANT de lancer le moteur, sinon le démarrage n'est pas mesuré.
 */
object CrankWatch {
    private const val MAX_RECORDS = 40
    private const val CRANK_WINDOW_MS = 6_000L
    private const val KEEP_MS = 30_000L

    private lateinit var prefs: SharedPreferences
    private val _history = MutableStateFlow<List<CrankRecord>>(emptyList())

    /** Démarrages mesurés, le plus récent d'abord. */
    val history: StateFlow<List<CrankRecord>> = _history.asStateFlow()

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var wasRunning: Boolean? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("battery", Context.MODE_PRIVATE)
        _history.value = decode(prefs.getString("starts", null))
    }

    /** Seuils classiques d'un test au démarreur (9,6 V mini) avec une marge « à surveiller ». */
    fun judge(restV: Float, minV: Float): CrankVerdict = when {
        minV < 9.6f || restV < 12.0f -> CrankVerdict.WEAK
        minV < 10.2f || restV < 12.3f -> CrankVerdict.FAIR
        else -> CrankVerdict.GOOD
    }

    /** Oublie l'état « moteur tournant / arrêté » (nouvelle connexion OBD) : pas de faux démarrage. */
    @Synchronized
    fun resetSession() {
        wasRunning = null
        samples.clear()
    }

    /**
     * À appeler à chaque tour de la boucle OBD. [volts] peut être nul (pas lu à ce tour), [rpm] nul si le
     * calculateur n'a pas répondu (fréquent pendant le démarreur). Renvoie le démarrage s'il vient d'être mesuré.
     */
    @Synchronized
    fun onSample(volts: Float?, rpm: Int?, coolantC: Int?, nowMs: Long = System.currentTimeMillis()): CrankRecord? {
        volts?.let { samples.addLast(nowMs to it) }
        while (samples.isNotEmpty() && nowMs - samples.first().first > KEEP_MS) samples.removeFirst()
        if (rpm == null) return null
        val running = rpm > 400
        val before = wasRunning
        wasRunning = running
        if (before != false || !running) return null

        val crank = samples.filter { nowMs - it.first <= CRANK_WINDOW_MS }
        val rest = samples.filter { nowMs - it.first > CRANK_WINDOW_MS }
        // Mesure incomplète (boîtier connecté trop tard, lectures manquées) : on ne juge pas.
        if (crank.size < 3 || rest.isEmpty()) return null
        val record = CrankRecord(nowMs, rest.maxOf { it.second }, crank.minOf { it.second }, coolantC)
        val list = (listOf(record) + _history.value).take(MAX_RECORDS)
        _history.value = list
        if (::prefs.isInitialized) prefs.edit().putString("starts", encode(list)).apply()
        return record
    }

    /** Écart de la tension mini entre les 5 derniers démarrages et les 5 d'avant (null s'il en manque). */
    fun trend(list: List<CrankRecord>): Float? {
        if (list.size < 10) return null
        return list.take(5).map { it.minV }.average().toFloat() - list.drop(5).take(5).map { it.minV }.average().toFloat()
    }

    /** Exemple affiché en mode démo (jamais enregistré). */
    fun demoHistory(now: Long = System.currentTimeMillis()): List<CrankRecord> {
        val day = 86_400_000L
        val mins = listOf(10.6f, 10.5f, 10.6f, 10.4f, 10.5f, 10.3f, 10.4f, 10.2f, 10.3f, 10.1f)
        return mins.mapIndexed { i, m -> CrankRecord(now - i * day - 7 * 3_600_000L, 12.55f - i * 0.02f, m, if (i % 3 == 0) 6 else 45) }
    }

    private fun encode(list: List<CrankRecord>) = JSONArray(list.map {
        JSONObject().put("t", it.atMs).put("rest", it.restV.toDouble()).put("min", it.minV.toDouble())
            .put("eau", it.coolantC ?: -999)
    }).toString()

    private fun decode(s: String?): List<CrankRecord> {
        if (s == null) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CrankRecord(
                    o.getLong("t"), o.getDouble("rest").toFloat(), o.getDouble("min").toFloat(),
                    o.optInt("eau", -999).takeIf { it != -999 },
                )
            }
        }.getOrDefault(emptyList())
    }
}
