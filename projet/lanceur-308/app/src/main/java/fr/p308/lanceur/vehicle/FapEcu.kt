package fr.p308.lanceur.vehicle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Une lecture du FAP faite dans le calculateur moteur (identifiant KWP 21 CA). */
data class FapEcuReading(
    val atMs: Long,
    /** Charge du filtre à particules (%). */
    val loadPct: Int,
    /** Pression différentielle à l'échappement, en amont / en aval du FAP (mbar). */
    val pressureMbar: Int,
    /** Température en aval du catalyseur (°C). */
    val cataC: Int,
    /** Km parcourus depuis la dernière régénération (pas de 25,7 km). */
    val kmSinceRegen: Int,
    /** Distance moyenne entre régénérations sur les 5 derniers cycles (km). */
    val avgKmBetween: Int,
)

/**
 * v0.44 : lecture directe du FAP dans le calculateur moteur, d'après PSA-RE (github.com/prototux/PSA-RE, licence
 * Apache 2.0 : diag/AEE2004.ev/CMM/EDC16C3/lids/CA.yml, variantes « T7_* » = 308 phase 1). Identifiant 21 CA,
 * 17 octets après « 61 CA », octet 1 = premier après l'identifiant (vérifié sur 21FE et 21DF de cette voiture) :
 * - octet 6 : température aval catalyseur × 3,5294 °C ;
 * - octet 7 : pression différentielle échappement × 3,9216 mbar ;
 * - octet 9 : charge du filtre × 0,3922 % ;
 * - octet 16 : distance moyenne entre régénérations (5 derniers cycles) × 25,681 km ;
 * - octet 17 : km depuis la dernière régénération × 25,681 km.
 *
 * Le 09/11, 21CA a été refusé (7F 21 12) dans le dialogue ouvert par « 81 » ; PSA-RE indique la session PSA
 * « 10 C0 », jamais essayée après « 81 » (le 08/11 elle l'avait été seule, et refusée). Le Lanceur essaie donc
 * 81 → 10 C0 → 21 CA. Lecture seule : aucune commande d'écriture, d'effacement ni d'accès sécurisé.
 * Refus : réponses brutes au journal ([FAP-CMM]) et plus d'essai avant le lendemain.
 */
object FapEcu {
    private var prefs: SharedPreferences? = null
    private val _last = MutableStateFlow<FapEcuReading?>(null)
    val last: StateFlow<FapEcuReading?> = _last.asStateFlow()

    /** Pas de nouvel essai avant cette heure après un refus (ms). */
    @Volatile
    var refusedUntilMs: Long = 0L
        private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("fap", Context.MODE_PRIVATE)
        prefs = p
        refusedUntilMs = p.getLong("ecuRefusedUntil", 0L)
        _last.value = p.getString("ecuLast", null)?.let(::decodePref)
    }

    /** Octets d'une réponse ELM327 (une ou plusieurs trames « 0: … 1: … », en-têtes masqués). */
    fun elmBytes(resp: String): List<Int> {
        val hex = buildString {
            for (raw in resp.uppercase().split('\r', '\n')) {
                var t = raw.replace(" ", "").trim()
                if (t.isEmpty()) continue
                if (Regex("^[0-9A-F]:").containsMatchIn(t)) t = t.substring(2)
                else if (Regex("^[0-9A-F]{3}$").matches(t)) continue // longueur totale d'une réponse multi-trames
                if (!Regex("^[0-9A-F]+$").matches(t)) continue
                append(t)
            }
        }
        return hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
    }

    /** Données qui suivent « 61 LID » dans la réponse, ou null (refus 7F, réponse vide ou coupée). */
    fun kwpData(resp: String, lid: Int): IntArray? {
        val b = elmBytes(resp)
        for (i in 0 until b.size - 1) if (b[i] == 0x61 && b[i + 1] == lid) return b.drop(i + 2).toIntArray()
        return null
    }

    /** Lecture de 21 CA décodée (pur, vérifié par FapEcuTest), ou null si trop courte. */
    fun decodeCA(d: IntArray, atMs: Long): FapEcuReading? {
        if (d.size < 17) return null
        fun b(n: Int) = d[n - 1]
        return FapEcuReading(
            atMs = atMs,
            loadPct = (b(9) * 0.3922).roundToInt(),
            pressureMbar = (b(7) * 3.9216).roundToInt(),
            cataC = (b(6) * 3.5294).roundToInt(),
            kmSinceRegen = (b(17) * 25.681).roundToInt(),
            avgKmBetween = (b(16) * 25.681).roundToInt(),
        )
    }

    /** Régénération terminée d'après le calculateur : les km depuis la dernière repassent près de zéro. */
    fun regenDone(prev: FapEcuReading?, now: FapEcuReading): Boolean =
        prev != null && prev.kmSinceRegen >= 50 && now.kmSinceRegen < 30

    /** Nouvelle lecture reçue (appelé par la boucle OBD). */
    @Synchronized
    fun onReading(r: FapEcuReading, odometerKm: Int?) {
        val prev = _last.value
        _last.value = r
        prefs?.edit()?.putString("ecuLast", encodePref(r))?.apply()
        ObdLog.log("FAP-CMM", "charge ${r.loadPct} %, pression différentielle ${r.pressureMbar} mbar, " +
            "${r.kmSinceRegen} km depuis la dernière régénération (moyenne ${r.avgKmBetween} km), aval catalyseur ${r.cataC} °C")
        if (regenDone(prev, r)) {
            ObdLog.log("FAP-CMM", "régénération terminée selon le calculateur (km depuis la dernière : ${prev?.kmSinceRegen} → ${r.kmSinceRegen})")
            FapRegen.onEcuRegenDone(r.atMs, odometerKm)
        }
    }

    /** Refus du calculateur : noté, plus d'essai pendant 24 h. */
    @Synchronized
    fun onRefused(detail: String, nowMs: Long = System.currentTimeMillis()) {
        refusedUntilMs = nowMs + 24 * 3_600_000L
        prefs?.edit()?.putLong("ecuRefusedUntil", refusedUntilMs)?.apply()
        ObdLog.log("FAP-CMM", "lecture du FAP refusée ($detail) : nouvel essai demain")
    }

    private fun encodePref(r: FapEcuReading) =
        listOf(r.atMs, r.loadPct, r.pressureMbar, r.cataC, r.kmSinceRegen, r.avgKmBetween).joinToString(",")

    private fun decodePref(s: String): FapEcuReading? = runCatching {
        val f = s.split(',')
        FapEcuReading(f[0].toLong(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt())
    }.getOrNull()
}
