package fr.p308.lanceur.vehicle

/**
 * PROFIL DE LA VOITURE (v0.24) : tout ce qui change d'une voiture à l'autre est ICI, et seulement ici.
 *
 * Pour adapter l'appli à une autre voiture, une IA (ou une personne) ne modifie que ce fichier, en suivant ADAPTER.md
 * à la racine du dépôt. Les valeurs ci-dessous sont celles de la voiture de l'auteur : Peugeot 308 phase 1 (T7) de 2007,
 * 1.6 HDi 110 (moteur DV6TED4, calculateur Bosch EDC16C34), diesel, boîte manuelle 5 rapports, pneus 205/55 R16.
 *
 * Ce qui n'est PAS ici, car réglable dans l'appli elle-même : couleur, 3 ou 5 portes, plaque, kilométrage, dates
 * d'entretien, intervalles d'entretien (onglet Entretien).
 */
object CarProfile {
    /** Nom affiché en haut de l'accueil et dans l'écran de bienvenue. */
    const val NAME = "Peugeot 308"

    /** Moteur affiché sous le nom (onglet Véhicule). */
    const val ENGINE_LABEL = "1.6 HDi 110"

    /**
     * Couple maximal du moteur (N·m) selon le régime (tr/min), points de la courbe constructeur, dans l'ordre croissant.
     * Sert à estimer le couple et la puissance en direct : couple = couple maxi au régime × charge moteur (PID 04).
     */
    val TORQUE_CURVE: List<Pair<Int, Int>> = listOf(
        800 to 120, 1000 to 150, 1500 to 215, 1750 to 240, 2750 to 240,
        3000 to 232, 3500 to 213, 4000 to 191, 4500 to 160, 5000 to 120,
    )

    /**
     * Vitesse en km/h à 1 000 tr/min pour chaque rapport, de la 1re à la dernière (autant de valeurs que de rapports).
     * Valeurs de départ seulement : l'appli apprend ensuite le vrai étagement sur la route (GearCalib).
     * Boîte automatique : laisser les valeurs, le rapport affiché sera seulement approché.
     */
    val KMH_PER_1000: FloatArray = floatArrayOf(7.9f, 14.6f, 22.9f, 31.6f, 40.6f)

    /** Contenance du réservoir (litres) et quantité restante quand le témoin de réserve s'allume (litres). */
    const val TANK_L = 60.0
    const val RESERVE_L = 6.0

    /** Filtre à particules (diesel récent) : détection des régénérations. false pour une essence ou un diesel sans FAP. */
    const val HAS_DPF = true

    /** Moteur turbo : conseil « attends N s avant de couper ». false pour un moteur atmosphérique. */
    const val HAS_TURBO = true

    /**
     * Lecture du kilométrage dans le calculateur moteur, propre aux PSA (Peugeot, Citroën) à calculateur Bosch EDC16
     * (requête KWP 21 10 vers l'adresse 6A8). false pour toute autre voiture : le kilométrage se saisit alors à la main
     * (Réglages → Kilométrage) et avance ensuite avec le GPS.
     */
    const val PSA_ECU_ODOMETER = true
}
