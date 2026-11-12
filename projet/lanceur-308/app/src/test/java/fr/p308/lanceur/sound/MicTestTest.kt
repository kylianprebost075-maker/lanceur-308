package fr.p308.lanceur.sound

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** En-tête du fichier WAV de l'essai du micro : un lecteur du PC doit pouvoir l'ouvrir tel quel. */
class MicTestTest {
    @Test
    fun enTeteWavMono16Bits() {
        val h = MicTest.wavHeader(16000, 32000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 32000, b.getInt(4))
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16000, b.getInt(24))            // fréquence
        assertEquals(32000, b.getInt(28))            // octets par seconde
        assertEquals(16, b.getShort(34).toInt())     // bits
        assertEquals(32000, b.getInt(40))            // taille du son
    }
}
