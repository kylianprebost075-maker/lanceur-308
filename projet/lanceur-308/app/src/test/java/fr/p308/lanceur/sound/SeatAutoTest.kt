package fr.p308.lanceur.sound

import fr.p308.lanceur.vehicle.Opening
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Passage automatique en « Passagers » quand une porte passager s'ouvre (v0.32). */
class SeatAutoTest {
    @Test fun passengerDoorSwitches() {
        assertTrue(SeatAuto.shouldSwitch(setOf(Opening.FRONT_RIGHT), SeatMode.SOLO, true, 0f))
        assertTrue(SeatAuto.shouldSwitch(setOf(Opening.REAR_LEFT), SeatMode.SOLO, true, 3f))
    }

    @Test fun driverDoorTrunkOrMovingDoNot() {
        assertFalse(SeatAuto.shouldSwitch(setOf(Opening.FRONT_LEFT), SeatMode.SOLO, true, 0f))
        assertFalse(SeatAuto.shouldSwitch(setOf(Opening.TRUNK, Opening.HOOD), SeatMode.SOLO, true, 0f))
        assertFalse(SeatAuto.shouldSwitch(setOf(Opening.REAR_RIGHT), SeatMode.SOLO, true, 30f))
        assertFalse(SeatAuto.shouldSwitch(setOf(Opening.REAR_RIGHT), SeatMode.SOLO, false, 0f)) // option coupée
        assertFalse(SeatAuto.shouldSwitch(setOf(Opening.REAR_RIGHT), SeatMode.PASSENGERS, true, 0f)) // déjà fait
    }

    @Test fun revertOnlyWhenAutomaticAndJustBooted() {
        assertTrue(SeatAuto.shouldRevert(SeatMode.PASSENGERS, true, 60_000))
        assertFalse(SeatAuto.shouldRevert(SeatMode.PASSENGERS, false, 60_000)) // choisi à la main
        assertFalse(SeatAuto.shouldRevert(SeatMode.PASSENGERS, true, 20 * 60_000)) // service relancé en route
        assertFalse(SeatAuto.shouldRevert(SeatMode.SOLO, true, 60_000))
    }
}
