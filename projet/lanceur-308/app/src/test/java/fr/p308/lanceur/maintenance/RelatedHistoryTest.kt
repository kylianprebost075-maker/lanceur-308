package fr.p308.lanceur.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.42 : interventions de l'historique liées à un entretien (fiche détail). */
class RelatedHistoryTest {
    private fun h(title: String, details: String = "") = HistoryEntry(date = null, km = null, title = title, details = details)

    private val history = listOf(
        h("Vidange + filtres (faite soi-même)", "Motul 0W-30, filtre à huile Purflux, filtre à gasoil Purflux, filtre à air Mann C3282"),
        h("Pneus avant, rotules, géométrie", "2 Michelin Primacy 5, 2 rotules inférieures"),
        h("Freins : disques et plaquettes", "Disques avec roulement et plaquettes arrière, plaquettes avant"),
        h("Vidange (faite soi-même)", "Motul 0W-30, filtre à huile, joint de bouchon de vidange, filtre d'habitacle"),
        h("Contrôle technique favorable", "Valable jusqu'au 18/06/2027"),
        h("Distribution + pompe à eau", "Kit distribution et pompe à eau, courroie d'accessoires, filtres à huile, à air et à gasoil"),
    )

    private fun titles(name: String) = MaintenanceItem(name = name).relatedHistory(history).map { it.title }

    @Test fun vidangeRetrouveLesVidanges() {
        val t = titles("Vidange + filtre à huile")
        assertTrue("Vidange + filtres (faite soi-même)" in t)
        assertTrue("Vidange (faite soi-même)" in t)
        assertTrue("Freins : disques et plaquettes" !in t)
    }

    @Test fun filtreAAirDansUneListeAuPluriel() {
        // « filtres à huile, à air et à gasoil » : pluriel et liste, le mot « air » doit être entier.
        assertEquals(
            listOf("Vidange + filtres (faite soi-même)", "Distribution + pompe à eau"),
            titles("Filtre à air"),
        )
        assertEquals(listOf("Vidange (faite soi-même)"), titles("Filtre d'habitacle"))
    }

    @Test fun liquideDeFreinNeRamenePasLesPlaquettes() {
        assertTrue(titles("Liquide de frein").isEmpty())
    }

    @Test fun plusieursMotsEtAccents() {
        assertEquals(listOf("Contrôle technique favorable"), titles("Contrôle technique"))
        assertEquals(listOf("Distribution + pompe à eau"), titles("Courroie de distribution + pompe à eau"))
        assertEquals(listOf("Pneus avant, rotules, géométrie"), titles("Pneus avant"))
    }

    @Test fun nomSansMotUtileNeRameneRien() {
        assertTrue(titles("+").isEmpty())
        assertTrue(titles("Assurance (échéance)").isEmpty())
    }
}
