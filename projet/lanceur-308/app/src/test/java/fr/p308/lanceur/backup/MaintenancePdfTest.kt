package fr.p308.lanceur.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mise en lignes du carnet d'entretien PDF (v0.26) : rien ne dépasse de la page, rien n'est perdu. */
class MaintenancePdfTest {
    /** Largeur d'essai : 1 point par caractère. */
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test fun texteCourtSurUneLigne() {
        assertEquals(listOf("Vidange et filtre"), MaintenancePdf.wrap("Vidange et filtre", 40f, measure))
    }

    @Test fun texteLongCoupeEntreLesMots() {
        val lines = MaintenancePdf.wrap("Remplacement des disques et plaquettes avant, purge du liquide de frein", 25f, measure)
        assertTrue(lines.size >= 3)
        assertTrue("aucune ligne ne dépasse", lines.all { it.length <= 25 })
        assertEquals("aucun mot perdu", "Remplacement des disques et plaquettes avant, purge du liquide de frein", lines.joinToString(" "))
    }

    @Test fun motPlusLongQueLaLigneEstCoupe() {
        val lines = MaintenancePdf.wrap("ref ABCDEFGHIJKLMNOPQRSTUVWXYZ fin", 10f, measure)
        assertTrue(lines.all { it.length <= 10 })
        assertEquals("refABCDEFGHIJKLMNOPQRSTUVWXYZfin", lines.joinToString("").replace(" ", ""))
    }

    @Test fun retoursALaLigneEtVide() {
        assertEquals(listOf("un", "deux"), MaintenancePdf.wrap("un\ndeux", 40f, measure))
        assertTrue(MaintenancePdf.wrap("", 40f, measure).isEmpty())
    }
}
