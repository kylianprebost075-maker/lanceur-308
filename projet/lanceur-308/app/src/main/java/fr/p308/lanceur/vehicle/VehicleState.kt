package fr.p308.lanceur.vehicle

enum class Opening(val label: String, val feminine: Boolean, val rear: Boolean = false) {
    FRONT_LEFT("Porte avant gauche", true),
    FRONT_RIGHT("Porte avant droite", true),
    REAR_LEFT("Porte arrière gauche", true, rear = true),
    REAR_RIGHT("Porte arrière droite", true, rear = true),
    TRUNK("Coffre", false),
    HOOD("Capot", false),
}

data class VehicleState(
    val open: Set<Opening> = emptySet(),
    val odometerKm: Int? = null,
    val odometerFromCan: Boolean = false,
    val outsideTempC: Float? = null,
    /** Vrai si la température extérieure est estimée par l'air d'admission (OBD), pas lue sur le CAN. */
    val outsideTempEstimated: Boolean = false,
    val batteryV: Float? = null,
    val fuelRangeKm: Int? = null,
    val reverse: Boolean = false,
    val fogLights: Boolean = false,
    /** Feux allumés (null tant que le CAN ne le fournit pas) : sert au thème nuit automatique. */
    val headlights: Boolean? = null,
    /** Capteurs de recul, de gauche à droite, en cm (null = rien détecté). */
    val rearRadarCm: List<Int?> = emptyList(),
    val source: String = "Aucune",
)

fun openingsFor(fiveDoors: Boolean) = Opening.entries.filter { fiveDoors || !it.rear }

fun describeOpen(open: Set<Opening>): String = when (open.size) {
    0 -> "Tout est fermé"
    1 -> open.first().let { "${it.label} ${if (it.feminine) "ouverte" else "ouvert"}" }
    else -> "${open.size} ouvrants ouverts : " + open.joinToString(", ") { it.label.lowercase() }
}
