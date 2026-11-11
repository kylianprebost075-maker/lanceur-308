package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tension lue par le boîtier CAN et rappel « moteur coupé, autoradio allumé » (v0.31). */
class BatteryWatchTest {
    @Before fun reset() { BatteryWatch.update(null, null, 0) }

    @Test fun decodeFytFromRealReading() {
        assertEquals(14.6f, BatteryWatch.decodeFyt(142)!!, 0.01f) // relevé du 11/11, moteur tournant
        assertEquals(12.4f, BatteryWatch.decodeFyt(120)!!, 0.01f)
        assertNull(BatteryWatch.decodeFyt(0))
        assertNull(BatteryWatch.decodeFyt(500))
    }

    @Test fun drainCountsMinutesEngineOff() {
        val t0 = 1_000_000L
        assertEquals(0, BatteryWatch.update(12.4f, null, t0).engineOffMin)
        val s = BatteryWatch.update(12.3f, null, t0 + 21 * 60_000)
        assertEquals(21, s.engineOffMin)
        assertEquals(1, BatteryWatch.drainStep(s.engineOffMin))
        assertTrue(BatteryWatch.drainMessage(s)!!.contains("21 min"))
        assertEquals(2, BatteryWatch.drainStep(41))
    }

    @Test fun engineRunningResetsDrain() {
        val t0 = 1_000_000L
        BatteryWatch.update(12.4f, null, t0)
        assertNull(BatteryWatch.update(14.4f, null, t0 + 30 * 60_000).engineOffMin) // alternateur : moteur tournant
        assertEquals(0, BatteryWatch.update(12.5f, null, t0 + 31 * 60_000).engineOffMin)
        assertNull(BatteryWatch.drainMessage(BatteryState(12.5f, BatteryLevel.OK, 5)))
    }

    @Test fun obdRpmWinsOverVoltage() {
        // Régime OBD connu : moteur tournant même si la tension est basse.
        assertNull(BatteryWatch.update(12.6f, 800, 0).engineOffMin)
    }
}
