package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Attente au démarrage pour le graissage du turbo. */
class TurboStartTest {
    private var now = 1_000_000L
    private val events = mutableListOf<TurboStart.Event>()

    @Before fun propre() {
        TurboStart.reset()
        now = 1_000_000L
        events.clear()
    }

    /** [s] secondes de service, une par seconde. */
    private fun run(s: Int, engine: Boolean, kmh: Float = 0f, enabled: Boolean = true, base: Int = 30, outside: Float? = 10f) {
        repeat(s) {
            TurboStart.tick(engine, kmh, enabled, base, outside, now)?.let { events += it }
            now += 1000L
        }
    }

    @Test fun compteAReboursPuisPartir() {
        run(5, engine = false)
        run(1, engine = true)
        assertEquals(TurboStart.Kind.STARTED, events.single().kind)
        assertEquals(30, events.single().seconds)
        assertEquals(30, TurboStart.state.value.waitS)
        run(10, engine = true)
        assertEquals(20, TurboStart.state.value.waitS)
        run(20, engine = true)
        assertEquals(TurboStart.Kind.READY, events.last().kind)
        assertTrue(TurboStart.state.value.ready)
        assertEquals(0, TurboStart.state.value.waitS)
        run(TurboStart.READY_SHOW_MS.toInt() / 1000 + 1, engine = true)
        assertTrue("« tu peux partir » s'efface", !TurboStart.state.value.ready)
    }

    @Test fun plusLongQuandIlGele() {
        run(1, engine = true, outside = -3f)
        assertEquals(30 + TurboStart.COLD_EXTRA_S, events.single().seconds)
    }

    @Test fun partiAvantLaFin() {
        run(1, engine = true)
        run(11, engine = true)
        run(1, engine = true, kmh = 12f)
        val e = events.last()
        assertEquals(TurboStart.Kind.LEFT_EARLY, e.kind)
        assertEquals(12, e.seconds)
        assertEquals(0, TurboStart.state.value.waitS)
        assertTrue(!TurboStart.state.value.ready)
    }

    @Test fun redemarrageRapideSansAttente() {
        run(40, engine = true)               // premier démarrage, attente terminée
        events.clear()
        run(60, engine = false)              // arrêt d'une minute
        run(1, engine = true)
        assertTrue("arrêt court : pas d'attente", events.isEmpty())
        assertEquals(0, TurboStart.state.value.waitS)
    }

    @Test fun longArretAvecAttente() {
        run(40, engine = true)
        events.clear()
        run(10 * 60, engine = false)         // arrêt de 10 min
        run(1, engine = true)
        assertEquals(TurboStart.Kind.STARTED, events.single().kind)
    }

    @Test fun tensionQuiHesiteNeRelancePas() {
        run(3, engine = true)
        events.clear()
        run(5, engine = false)               // 5 s sans signe de marche : pas un arrêt
        run(3, engine = true)
        assertTrue(events.isEmpty())
        assertTrue("le compte continue", TurboStart.state.value.waitS in 1..30)
    }

    @Test fun desactiveOuDejaEnRoute() {
        run(1, engine = true, enabled = false)
        assertTrue(events.isEmpty())
        TurboStart.reset(); events.clear()
        run(1, engine = true, kmh = 50f)     // autoradio relancé en roulant
        assertTrue(events.isEmpty())
        assertEquals(0, TurboStart.state.value.waitS)
    }

    @Test fun apercuSansMoteur() {
        TurboStart.preview(20, 10f, now)
        assertEquals(20, TurboStart.state.value.waitS)
        run(21, engine = false)
        assertTrue("aperçu : rien au journal ni à la voix", events.isEmpty())
        assertTrue(TurboStart.state.value.ready)
        assertNull(events.firstOrNull())
    }
}
