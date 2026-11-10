package fr.p308.lanceur.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32

/** Remplacement de la plaque dans le modèle 3D (v0.23) : le fichier garde sa taille, seul le dessin de la plaque change. */
class GlbPlateTest {
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val body = type.toByteArray(Charsets.US_ASCII) + data
        d.write(body)
        d.writeInt(CRC32().apply { update(body) }.value.toInt())
        return out.toByteArray()
    }

    /** Un faux PNG bien formé (en-tête, données quelconques, fin) aux dimensions voulues. */
    private fun png(width: Int, height: Int, fill: Int, dataSize: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr.toByteArray()) + chunk("IDAT", ByteArray(dataSize) { fill.toByte() }) + chunk("IEND", ByteArray(0))
    }

    /** Un faux modèle : du texte, une autre image, la plaque (avec de la place en réserve), puis d'autres données. */
    private fun model(plate: ByteArray) =
        "glTF{\"images\":[]}".toByteArray() + png(64, 64, 7, 50) + ByteArray(9) { 1 } + plate + ByteArray(33) { 2 }

    @Test fun trouveLaPlaqueParmiLesImages() {
        val plate = GlbPlate.pad(png(1040, 220, 3, 100), 4096)!!
        val glb = model(plate)
        assertEquals(2, GlbPlate.findPngs(glb).size)
        val slot = GlbPlate.plateSlot(glb)!!
        assertEquals(4096, slot.length)
        assertArrayEquals(plate, glb.copyOfRange(slot.start, slot.start + slot.length))
    }

    @Test fun leRemplissageDonneUnPngValideDeLaBonneTaille() {
        val padded = GlbPlate.pad(png(1040, 220, 3, 100), 1000)!!
        assertEquals(1000, padded.size)
        // Relu comme un PNG entier : il va bien jusqu'à son bloc de fin.
        assertEquals(1000, GlbPlate.findPngs(padded).single().length)
        // Le contrôle (CRC) du bloc de remplissage est juste.
        val head = png(1040, 220, 3, 100).size - 12
        val len = DataInputAt(padded, head)
        val crc = CRC32().apply { update(padded, head + 4, 4 + len) }.value.toInt()
        assertEquals(crc, DataInputAt(padded, head + 8 + len))
    }

    private fun DataInputAt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    @Test fun remplacerNeChangeQueLaPlaque() {
        val glb = model(GlbPlate.pad(png(1040, 220, 3, 100), 4096)!!)
        val slot = GlbPlate.plateSlot(glb)!!
        val out = GlbPlate.replace(glb, png(1040, 220, 9, 700))!!
        assertEquals(glb.size, out.size)
        assertArrayEquals(glb.copyOfRange(0, slot.start), out.copyOfRange(0, slot.start))
        assertArrayEquals(glb.copyOfRange(slot.start + slot.length, glb.size), out.copyOfRange(slot.start + slot.length, out.size))
        assertEquals(slot, GlbPlate.plateSlot(out))
        // On peut recommencer avec une autre plaque.
        assertNotNull(GlbPlate.replace(out, png(1040, 220, 5, 3000)))
    }

    @Test fun refuseCeQuiNeRentrePas() {
        val glb = model(GlbPlate.pad(png(1040, 220, 3, 100), 4096)!!)
        assertNull("image plus grosse que la place", GlbPlate.replace(glb, png(1040, 220, 9, 5000)))
        assertNull("il manque moins que la taille d'un bloc", GlbPlate.pad(png(1040, 220, 3, 100), png(1040, 220, 3, 100).size + 5))
        assertNull("modèle sans plaque", GlbPlate.replace("glTF".toByteArray() + png(64, 64, 7, 50), png(1040, 220, 9, 10)))
        assertNull("fichier qui n'est pas un PNG", GlbPlate.pad(ByteArray(200), 4096))
    }

    /** Le vrai modèle de l'appli, s'il est là (il n'est pas publié) : il doit avoir une place de plaque assez grande. */
    @Test fun leModeleEmbarqueAUnePlaceDePlaque() {
        val f = File("src/main/assets/models/car.glb")
        if (!f.exists() || f.length() == 0L) return
        val slot = GlbPlate.plateSlot(f.readBytes())
        assertNotNull("pas d'image 1040 x 220 dans car.glb", slot)
        assert(slot!!.length >= 100_000) { "place trop petite : ${slot.length} octets (modèle public pas refait ?)" }
    }
}
