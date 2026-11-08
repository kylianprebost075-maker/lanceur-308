package fr.p308.lanceur.maintenance

import fr.p308.lanceur.drive.TripRecord
import fr.p308.lanceur.vehicle.CrankRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Santé de la batterie déduite de l'historique des démarrages : 0 = bonne, 1 = à surveiller, 2 = à changer. */
data class BatteryHealth(val level: Int, val title: String, val text: String)

object BatteryHealthModel {
    private const val DAY = 86_400_000L

    /** Tension mini au démarreur ramenée à un moteur tiède : le froid la fait baisser sans que la batterie soit en cause. */
    private fun corrected(r: CrankRecord): Float? = r.minV?.let { v ->
        val c = r.coolantC ?: 20
        v + when {
            c < 0 -> 0.5f
            c < 10 -> 0.3f
            c < 20 -> 0.15f
            else -> 0f
        }
    }

    private fun median(v: List<Float>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /**
     * Compare les 5 derniers démarrages aux plus anciens. Repères : 9,6 V mini au démarreur et 12,2 V au repos
     * (batterie à changer) ; baisse de 0,3 V ou 10,2 V / 12,35 V (à surveiller). Si la tension mini baisse
     * régulièrement, estime dans combien de semaines elle passerait sous 9,6 V. null s'il y a moins de 4 démarrages.
     */
    fun evaluate(list: List<CrankRecord>, nowMs: Long = System.currentTimeMillis()): BatteryHealth? {
        if (list.size < 4) return null
        val recent = list.take(5)
        val older = list.drop(5).take(15)
        val recMin = median(recent.mapNotNull(::corrected))
        val recRest = median(recent.map { it.restV }) ?: return null
        val oldMin = median(older.mapNotNull(::corrected))
        val drop = if (recMin != null && oldMin != null) oldMin - recMin else null

        // Vitesse de la baisse (V par jour) entre le milieu des anciens et le milieu des récents.
        var weeksLeft: Int? = null
        if (drop != null && drop > 0.05f && recMin != null && recMin > 9.6f) {
            val days = (recent[recent.size / 2].atMs - older[older.size / 2].atMs) / DAY.toFloat()
            if (days >= 3f) weeksLeft = ((recMin - 9.6f) / (drop / days) / 7f).roundToInt().coerceAtMost(200)
        }
        val winter = LocalDate.now().monthValue.let { it >= 10 || it <= 2 }
        val level = when {
            (recMin != null && recMin < 9.8f) || recRest < 12.2f -> 2
            (drop ?: 0f) >= 0.3f || (recMin != null && recMin < 10.2f) || recRest < 12.35f || (weeksLeft ?: 999) < 26 -> 1
            else -> 0
        }
        val values = (recMin?.let { "mini ≈ %.1f V au démarreur, ".format(java.util.Locale.FRANCE, it) } ?: "") +
            "repos %.2f V".format(java.util.Locale.FRANCE, recRest)
        val text = when (level) {
            2 -> "$values. Elle ne tiendra plus longtemps : fais-la tester et prévois de la changer" +
                (if (winter) " avant les grands froids." else ".")
            1 -> "$values" + (drop?.takeIf { it >= 0.1f }?.let { ", en baisse de %.1f V".format(java.util.Locale.FRANCE, it) } ?: "") +
                ". " + (weeksLeft?.let { "Au rythme actuel, trop faible dans ≈ $it semaine(s). " } ?: "") +
                (if (winter) "Fais-la tester avant l'hiver." else "À surveiller.")
            else -> "$values. Elle tient bien la charge" +
                (weeksLeft?.let { " (baisse lente : ≈ $it semaines de marge)" } ?: "") + "."
        }
        val title = when (level) {
            2 -> "À changer bientôt"
            1 -> "À surveiller"
            else -> "Bonne santé"
        }
        return BatteryHealth(level, title, text)
    }
}

/**
 * Usure de l'huile selon l'usage : les petits trajets (moins de 10 km, l'huile n'a pas le temps de chauffer)
 * l'usent plus vite (eau et gazole s'y mélangent). Les carnets parlent d'« usage sévère » et réduisent
 * l'intervalle jusqu'à moitié. Ici : km comptés × (1 + 0,6 × part de petits trajets), donc au pire × 1,6.
 * Calculé sur les trajets enregistrés depuis la dernière vidange (au moins 8 trajets).
 */
data class OilWearInfo(val factor: Double, val shortPct: Int, val trips: Int)

object OilWear {
    @Volatile
    var info: OilWearInfo? = null
        private set

    fun isOil(item: MaintenanceItem) = item.name.contains("vidange", ignoreCase = true)

    fun recompute(items: List<MaintenanceItem>, trips: List<TripRecord>) {
        val oil = items.firstOrNull(::isOil)
        val since = oil?.lastDate
        val list = trips.filter { t ->
            since == null || !Instant.ofEpochMilli(t.startMs).atZone(ZoneId.systemDefault()).toLocalDate().isBefore(since)
        }
        if (list.size < 8) {
            info = null
            return
        }
        val short = list.count { it.distanceKm < 10.0 }
        val share = short.toDouble() / list.size
        info = OilWearInfo(1.0 + 0.6 * share, (share * 100).roundToInt(), list.size)
    }

    /** Km « effectifs » depuis la vidange : les km réels multipliés par le facteur d'usure. */
    fun effectiveKm(realKm: Int): Int = (realKm * (info?.factor ?: 1.0)).roundToInt()
}
