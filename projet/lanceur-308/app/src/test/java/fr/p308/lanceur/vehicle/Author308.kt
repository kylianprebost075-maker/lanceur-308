package fr.p308.lanceur.vehicle

import org.junit.Assume.assumeTrue

/**
 * Certains tests vérifient les valeurs de la voiture de l'auteur (Peugeot 308 1.6 HDi 110) : couple du moteur DV6,
 * étagement de sa boîte. Quand CarProfile.kt a été adapté à une autre voiture, ils sont IGNORÉS (pas en échec) ;
 * CarProfileTest vérifie alors que les nouvelles valeurs sont plausibles.
 */
fun onlyForAuthor308() = assumeTrue(
    "valeurs de la 308 de l'auteur : test ignoré pour une autre voiture",
    CarProfile.NAME == "Peugeot 308" && CarProfile.ENGINE_LABEL == "1.6 HDi 110",
)