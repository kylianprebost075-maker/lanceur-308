package fr.p308.lanceur.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des codes défaut (réponses du boîtier ELM327) et calcul de la puissance : vérifiés sans la voiture. */
class DtcAndPowerTest {
    @Test
    fun `reponse simple sans nombre de codes`() {
        assertEquals(listOf("P0133"), parseDtcs("43 01 33 00 00 00 00", 0x43))
    }

    @Test
    fun `reponse CAN avec le nombre de codes`() {
        assertEquals(listOf("P0133", "P0420"), parseDtcs("43 02 01 33 04 20", 0x43))
    }

    @Test
    fun `reponse en plusieurs trames`() {
        val resp = "00A\r0: 43 04 01 33 04\r1: 20 01 00 02 99 00 00\r"
        assertEquals(listOf("P0133", "P0420", "P0100", "P0299"), parseDtcs(resp, 0x43))
    }

    @Test
    fun `aucun code et reponses inutilisables`() {
        assertTrue(parseDtcs("43 00", 0x43).isEmpty())
        assertTrue(parseDtcs("NO DATA", 0x43).isEmpty())
        assertTrue(parseDtcs("", 0x43).isEmpty())
        assertTrue(parseDtcs("SEARCHING...\rUNABLE TO CONNECT", 0x47).isEmpty())
    }

    @Test
    fun `lettre du code selon les deux premiers bits`() {
        assertEquals(listOf("U0123"), parseDtcs("47 01 C1 23", 0x47))
        assertEquals(listOf("C1234"), parseDtcs("4A 01 52 34", 0x4A))
        assertEquals(listOf("B0001"), parseDtcs("43 01 80 01", 0x43))
    }

    @Test
    fun `un code repete n'est compte qu'une fois`() {
        assertEquals(listOf("P0401"), parseDtcs("43 02 04 01 04 01", 0x43))
    }

    @Test
    fun `couple maxi du DV6 selon le regime`() {
        onlyForAuthor308()
        assertEquals(240f, maxTorqueNm(1750), 0.01f)
        assertEquals(240f, maxTorqueNm(2000), 0.01f)
        assertEquals(120f, maxTorqueNm(500), 0.01f)
        assertEquals(120f, maxTorqueNm(6000), 0.01f)
        assertEquals(222.5f, maxTorqueNm(3250), 0.01f)
    }

    @Test
    fun `110 ch a 4000 tours pleine charge`() {
        onlyForAuthor308()
        val (torque, ch) = estimate(4000, 100)
        assertEquals(191, torque)
        assertEquals(109, ch)
        assertEquals(0 to 0, estimate(2000, 0))
    }
}
