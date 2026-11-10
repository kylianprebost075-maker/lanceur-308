package fr.p308.lanceur.vehicle

import kotlin.math.abs

/**
 * Rapport engagé et conseil de passage, calculés à partir du régime et de la vitesse (OBD).
 * Aucune donnée de boîte n'est lue : le rapport est ESTIMÉ avec l'étagement approché de la boîte 5 vitesses
 * du 1.6 HDi 110 (km/h pour 1 000 tr/min), à affiner après un essai sur route si un rapport est mal reconnu.
 */
object ShiftAdvice {
    /** km/h à 1 000 tr/min, 1re → 5e (valeurs approchées pour 308 1.6 HDi 110, pneus 205/55 R16). */
    val KMH_PER_1000 = CarProfile.KMH_PER_1000

    enum class Kind { NONE, UP, DOWN, COLD }

    data class Advice(val gear: Int?, val kind: Kind, val target: Int? = null) {
        val text: String
            get() = when (kind) {
                Kind.UP -> "▲ Passe la ${ordinal(target)}"
                Kind.DOWN -> "▼ Rétrograde en ${ordinal(target)}"
                Kind.COLD -> "Moteur froid : reste sous 2 500 tr/min"
                Kind.NONE -> ""
            }
    }

    fun ordinal(g: Int?): String = when (g) {
        null -> "—"
        1 -> "1re"
        else -> "${g}e"
    }

    /** Étagement utilisé : celui appris sur la route (GearCalib) dès qu'il existe, sinon les valeurs approchées. */
    private fun table(): FloatArray = GearCalib.learned ?: KMH_PER_1000

    /** Rapport le plus proche du rapport vitesse / régime, ou null (arrêt, embrayage, point mort). */
    fun gear(rpm: Int?, speedKmh: Int?): Int? {
        if (rpm == null || speedKmh == null || rpm < 600 || speedKmh < 4) return null
        val t = table()
        val ratio = speedKmh * 1000f / rpm
        val i = t.indices.minByOrNull { abs(t[it] - ratio) } ?: return null
        return if (abs(t[i] - ratio) / t[i] <= 0.12f) i + 1 else null
    }

    /** Régime qu'on aurait sur le rapport `g` à cette vitesse. */
    fun rpmOn(g: Int, speedKmh: Int): Int = (speedKmh * 1000f / table()[g - 1]).toInt()

    /**
     * Conseil « éco » d'un diesel : monter tôt (≈ 2 300 tr/min à charge modérée) sans descendre sous 1 300 tr/min
     * après le passage, monter de toute façon au-delà de 3 800 tr/min, rétrograder si le moteur peine sous 1 250 tr/min.
     */
    fun advise(rpm: Int?, speedKmh: Int?, loadPct: Int?, coolantC: Int?): Advice {
        val g = gear(rpm, speedKmh)
        if (rpm == null || speedKmh == null) return Advice(g, Kind.NONE)
        if (coolantC != null && coolantC < 60 && rpm > 2500) return Advice(g, Kind.COLD)
        if (g == null) return Advice(null, if (coolantC != null && coolantC < 60) Kind.COLD else Kind.NONE)
        val load = loadPct ?: 50
        if (g < KMH_PER_1000.size) {
            val after = rpmOn(g + 1, speedKmh)
            val wantUp = rpm > 3800 || (rpm > 2300 && load < 70)
            if (wantUp && after >= 1300) return Advice(g, Kind.UP, g + 1)
        }
        if (g > 1 && rpm < 1250 && load > 60) return Advice(g, Kind.DOWN, g - 1)
        if (coolantC != null && coolantC < 60) return Advice(g, Kind.COLD)
        return Advice(g, Kind.NONE)
    }
}
