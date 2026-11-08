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
    /** Kilomètres ajoutés par le GPS depuis le dernier relevé (calculateur ou saisie) : > 0 = valeur estimée. */
    val odometerGpsKm: Int = 0,
    /** Dernier relevé du kilométrage : valeur, date, et s'il vient du calculateur moteur (sinon saisi à la main). */
    val odometerBaseKm: Int? = null,
    val odometerReadAtMs: Long? = null,
    val odometerFromEcu: Boolean = false,
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

/** « 140 721 km », ou « ≈ 140 765 km » quand le GPS a ajouté des km depuis le dernier relevé. */
fun VehicleState.odometerText(): String? =
    odometerKm?.let { (if (odometerGpsKm > 0) "≈ " else "") + fr.p308.lanceur.maintenance.fmtKm(it) + " km" }

/** D'où vient le kilométrage affiché : « Relevé moteur du 8 nov. à 18:05 + 44 km au GPS ». */
fun VehicleState.odometerOrigin(): String? {
    val base = odometerBaseKm ?: return null
    val whenTxt = odometerReadAtMs?.let {
        " du " + java.text.SimpleDateFormat("d MMM 'à' HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it))
    } ?: ""
    val src = if (odometerFromEcu) "Relevé moteur" else "Saisi à la main"
    val gps = if (odometerGpsKm > 0) " (${fr.p308.lanceur.maintenance.fmtKm(base)} km) + $odometerGpsKm km au GPS" else ""
    return "$src$whenTxt$gps"
}

/** Version courte, pour une ligne : « +44 km GPS depuis le 8 nov. », « lu au moteur le 8 nov. ». */
fun VehicleState.odometerShortOrigin(): String? {
    if (odometerBaseKm == null) return null
    val day = odometerReadAtMs?.let { java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE).format(java.util.Date(it)) }
    return when {
        odometerGpsKm > 0 -> "+$odometerGpsKm km GPS" + (day?.let { " depuis le $it" } ?: "")
        odometerFromEcu -> "lu au moteur" + (day?.let { " le $it" } ?: "")
        else -> "saisi" + (day?.let { " le $it" } ?: "")
    }
}

fun openingsFor(fiveDoors: Boolean) = Opening.entries.filter { fiveDoors || !it.rear }

fun describeOpen(open: Set<Opening>): String = when (open.size) {
    0 -> "Tout est fermé"
    1 -> open.first().let { "${it.label} ${if (it.feminine) "ouverte" else "ouvert"}" }
    else -> "${open.size} ouvrants ouverts : " + open.joinToString(", ") { it.label.lowercase() }
}
