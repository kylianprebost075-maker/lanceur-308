package fr.p308.lanceur.sound

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Position conseillée du point dans la croix « Champ » de l'égaliseur FYT (com.syu.eq). */
data class StagePlan(
    /** Crans vers la droite (négatif = vers la gauche), de -8 à +8. */
    val rightSteps: Int,
    /** Crans vers l'avant (négatif = vers l'arrière), de -8 à +8. */
    val frontSteps: Int,
    /** De combien le tweeter le plus proche paraît plus fort (dB, ≥ 0). */
    val louderDb: Double,
    /** Écart de distance entre les deux tweeters (cm, ≥ 0). */
    val gapCm: Int,
    /** Côté du tweeter le plus proche. */
    val nearLeft: Boolean,
)

/**
 * Réglage de la scène sonore sur un autoradio FYT (v0.29). Relevé du 11/11 sur l'autoradio : son égaliseur n'a PAS de
 * retard par haut-parleur ; l'onglet « Champ » n'offre qu'une croix balance / fader de 8 crans de chaque côté, et ses
 * boutons sont des positions toutes faites (Mode de conduite = 4 à gauche et 4 devant, Copilote = 4 à droite et 4
 * devant, Arrière = 4 derrière, Tous = centre). On compense donc par le niveau : le tweeter le plus proche paraît plus
 * fort de 20·log10(d_loin / d_près) dB, on décale le point vers l'autre côté. Le nombre de dB par cran n'est pas connu :
 * [DB_PER_STEP] est une estimation, et l'écran demande de finir à l'oreille (bruit rose sur les deux voies).
 * Calcul pur, vérifié par FytStageTest.
 */
object FytStage {
    const val MAX_STEPS = 8
    /** Estimation, à confirmer à l'oreille ou au micro du PC. */
    const val DB_PER_STEP = 1.5
    /** La scène doit venir de devant : avant légèrement favorisé (réglage relevé chez l'auteur : 2 crans). */
    const val FRONT_STEPS = 2

    fun plan(distancesCm: List<Int>): StagePlan? {
        val left = distancesCm.getOrElse(Speaker.FL.ordinal) { 0 }
        val right = distancesCm.getOrElse(Speaker.FR.ordinal) { 0 }
        if (left <= 0 || right <= 0) return null
        val nearLeft = left < right
        val louder = 20 * log10(maxOf(left, right).toDouble() / minOf(left, right))
        val steps = (louder / DB_PER_STEP).roundToInt().coerceIn(0, MAX_STEPS)
        return StagePlan(
            rightSteps = if (nearLeft) steps else -steps,
            frontSteps = FRONT_STEPS,
            louderDb = louder,
            gapCm = abs(left - right),
            nearLeft = nearLeft,
        )
    }
}
