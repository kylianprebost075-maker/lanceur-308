package fr.p308.lanceur.drive

import fr.p308.lanceur.maintenance.Due
import fr.p308.lanceur.maintenance.DueLevel
import fr.p308.lanceur.maintenance.label
import fr.p308.lanceur.vehicle.BatteryState
import fr.p308.lanceur.vehicle.CrankRecord
import fr.p308.lanceur.vehicle.CrankVerdict
import java.util.Locale

/** Gravité d'une ligne du bilan « Prêt à partir ». */
enum class ReadyLevel { OK, WARN, ALERT }

/** Une ligne du bilan : sujet (« Batterie »), phrase, gravité. */
data class ReadyLine(val level: ReadyLevel, val title: String, val text: String)

/**
 * Bilan « Prêt à partir » affiché au démarrage (v0.26) : verglas, batterie, carburant, entretien. Il ne mesure rien
 * lui-même : il résume ce que l'appli sait déjà. Un sujet sans donnée n'a pas de ligne (rien n'est inventé).
 * Calcul pur, vérifié par ReadyCheckTest.
 */
object ReadyCheck {
    /** En dessous : risque de verglas (la chaussée est plus froide que l'air). */
    const val ICE_C = 3f
    const val FUEL_ALERT_KM = 50
    const val FUEL_WARN_KM = 100
    /** Un démarrage plus ancien ne dit plus rien de la batterie d'aujourd'hui. */
    private const val CRANK_MAX_AGE_MS = 21L * 24 * 3600 * 1000

    private fun v(x: Float) = "%.1f V".format(Locale.FRANCE, x)

    fun lines(
        outsideC: Float?,
        outsideEstimated: Boolean,
        battery: BatteryState,
        lastCrank: CrankRecord?,
        range: RangeInfo?,
        dues: List<Pair<String, Due>>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ReadyLine> = buildList {
        // Température lue sur l'autoradio seulement : une estimation par l'air d'admission est trop chaude pour le verglas.
        if (outsideC != null && !outsideEstimated) {
            val t = "%.0f °C dehors".format(Locale.FRANCE, outsideC)
            add(if (outsideC <= ICE_C) ReadyLine(ReadyLevel.ALERT, "Verglas", "Risque de verglas : $t")
            else ReadyLine(ReadyLevel.OK, "Température", t))
        }

        val alarm = battery.message
        val crank = lastCrank?.takeIf { nowMs - it.atMs in 0..CRANK_MAX_AGE_MS }
        when {
            alarm != null -> add(ReadyLine(ReadyLevel.ALERT, "Batterie", alarm))
            crank != null -> {
                val detail = crank.minV?.let { "${v(it)} au démarreur" } ?: "${v(crank.restV)} au repos"
                add(when (crank.verdict) {
                    CrankVerdict.WEAK -> ReadyLine(ReadyLevel.ALERT, "Batterie", "Faible au dernier démarrage ($detail)")
                    CrankVerdict.FAIR -> ReadyLine(ReadyLevel.WARN, "Batterie", "À surveiller ($detail)")
                    CrankVerdict.GOOD -> ReadyLine(ReadyLevel.OK, "Batterie", "Bonne ($detail)")
                })
            }
            battery.volts != null -> add(ReadyLine(ReadyLevel.OK, "Batterie", v(battery.volts)))
        }

        if (range != null) {
            val t = "≈ ${range.km} km d'autonomie"
            add(when {
                range.km < FUEL_ALERT_KM -> ReadyLine(ReadyLevel.ALERT, "Carburant", "Pense au plein : $t")
                range.km < FUEL_WARN_KM -> ReadyLine(ReadyLevel.WARN, "Carburant", t)
                else -> ReadyLine(ReadyLevel.OK, "Carburant", t)
            })
        }

        val late = dues.filter { it.second.level == DueLevel.LATE }
        val soon = dues.filter { it.second.level == DueLevel.SOON }
        fun names(list: List<Pair<String, Due>>) =
            list.take(3).joinToString(" ; ") { "${it.first} (${it.second.label().replaceFirstChar(Char::lowercase)})" } +
                if (list.size > 3) " ; + ${list.size - 3}" else ""
        when {
            late.isNotEmpty() -> add(ReadyLine(ReadyLevel.ALERT, "Entretien", names(late)))
            soon.isNotEmpty() -> add(ReadyLine(ReadyLevel.WARN, "Entretien", names(soon)))
            dues.any { it.second.level == DueLevel.OK } -> add(ReadyLine(ReadyLevel.OK, "Entretien", "À jour"))
        }
    }

    /** Titre du bilan : rassurant seulement si aucune ligne ne demande d'attention. */
    fun title(lines: List<ReadyLine>): String = when {
        lines.any { it.level == ReadyLevel.ALERT } -> "À vérifier avant de partir"
        lines.any { it.level == ReadyLevel.WARN } -> "Prêt à partir, avec un point à surveiller"
        else -> "Prêt à partir"
    }
}
