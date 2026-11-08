package fr.p308.lanceur.vehicle

/**
 * Explication en français des codes défaut normalisés (SAE J2012) les plus courants sur un 1.6 HDi.
 * Les codes « P1… » sont propres au constructeur : seul un outil PSA (ou un garage) donne leur sens exact.
 */
object DtcInfo {
    private val KNOWN = mapOf(
        "P0087" to "Pression du rail gazole trop basse",
        "P0088" to "Pression du rail gazole trop haute",
        "P0089" to "Régulateur de pression gazole : fonctionnement anormal",
        "P0093" to "Fuite de gazole importante détectée",
        "P0100" to "Débitmètre d'air : circuit",
        "P0101" to "Débitmètre d'air : valeur incohérente",
        "P0102" to "Débitmètre d'air : signal trop faible",
        "P0103" to "Débitmètre d'air : signal trop fort",
        "P0105" to "Capteur de pression d'admission : circuit",
        "P0106" to "Capteur de pression d'admission : valeur incohérente",
        "P0110" to "Capteur de température d'air : circuit",
        "P0115" to "Capteur de température d'eau : circuit",
        "P0116" to "Capteur de température d'eau : valeur incohérente",
        "P0117" to "Capteur de température d'eau : signal trop faible",
        "P0118" to "Capteur de température d'eau : signal trop fort",
        "P0180" to "Capteur de température de gazole : circuit",
        "P0190" to "Capteur de pression du rail : circuit",
        "P0191" to "Capteur de pression du rail : valeur incohérente",
        "P0201" to "Injecteur cylindre 1 : circuit",
        "P0202" to "Injecteur cylindre 2 : circuit",
        "P0203" to "Injecteur cylindre 3 : circuit",
        "P0204" to "Injecteur cylindre 4 : circuit",
        "P0234" to "Turbo : surpression",
        "P0299" to "Turbo : pression trop faible (manque de puissance)",
        "P0335" to "Capteur de régime vilebrequin : circuit",
        "P0340" to "Capteur d'arbre à cames : circuit",
        "P0380" to "Bougies de préchauffage : circuit",
        "P0400" to "Recirculation des gaz (EGR) : débit anormal",
        "P0401" to "Vanne EGR : débit insuffisant (souvent encrassée)",
        "P0402" to "Vanne EGR : débit excessif",
        "P0403" to "Vanne EGR : circuit de commande",
        "P0404" to "Vanne EGR : position incohérente",
        "P0409" to "Capteur de position de vanne EGR : circuit",
        "P0470" to "Capteur de pression d'échappement : circuit",
        "P0471" to "Capteur de pression différentielle FAP : valeur incohérente",
        "P0472" to "Capteur de pression différentielle FAP : signal trop faible",
        "P0473" to "Capteur de pression différentielle FAP : signal trop fort",
        "P0480" to "Motoventilateur : circuit de commande",
        "P0500" to "Capteur de vitesse du véhicule",
        "P0560" to "Tension de la batterie anormale",
        "P0562" to "Tension de la batterie trop basse",
        "P0563" to "Tension de la batterie trop haute",
        "P0602" to "Calculateur moteur : programmation",
        "P0605" to "Calculateur moteur : erreur mémoire interne",
        "P0627" to "Pompe à gazole : circuit de commande",
        "P0670" to "Boîtier de préchauffage : circuit",
        "P2002" to "Filtre à particules : efficacité insuffisante",
        "P2003" to "Filtre à particules : efficacité insuffisante",
        "P2263" to "Turbo : pression de suralimentation anormale",
        "P2463" to "Filtre à particules : colmatage (trop de suies)",
    )

    fun describe(code: String): String = KNOWN[code] ?: when {
        code.length == 5 && code[0] == 'P' && code[1] in "13" -> "Code propre à PSA : à faire lire par un garage ou un outil PSA"
        code.startsWith("P") -> "Moteur / transmission"
        code.startsWith("C") -> "Châssis (freins, direction)"
        code.startsWith("B") -> "Habitacle (airbag, confort)"
        code.startsWith("U") -> "Réseau de communication entre calculateurs"
        else -> "Code inconnu"
    }
}
