package fr.p308.lanceur.vehicle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Journal OBD lisible (un fichier texte par jour, 14 jours gardés) : connexions, coupures, chaque essai de lecture du
 * kilométrage (avec la réponse brute du calculateur), démarrages mesurés ou ratés, tensions. Copié sur la clé USB avec
 * la sauvegarde (dossier « journal »), pour comprendre ce qui s'est passé dans la voiture sans y être.
 * Jamais d'écriture en mode démo ; écritures disque en arrière-plan.
 */
object ObdLog {
    private const val KEEP_DAYS = 14L
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val pending = ArrayList<String>(64)

    @Volatile
    var dir: File? = null
        private set

    fun init(ctx: Context) {
        io.launch {
            val d = File(ctx.filesDir, "journal-obd").apply { mkdirs() }
            dir = d
            val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
            d.listFiles()?.filter { it.name.take(10) < oldest }?.forEach { it.delete() }
        }
    }

    /** Ajoute une ligne « HH:mm:ss [SUJET] texte ». Les détails techniques sont gardés tels quels. */
    fun log(topic: String, text: String) {
        if (Vehicle.demo) return
        val line = "${LocalDateTime.now().format(time)} [$topic] ${text.replace('\n', ' ').replace('\r', ' ').take(300)}"
        synchronized(pending) {
            if (pending.size < 2000) pending.add(line)
        }
        io.launch { flush() }
    }

    private fun flush() {
        val d = dir ?: return
        val batch: List<String>
        synchronized(pending) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
        }
        runCatching { File(d, "${LocalDate.now()}.txt").appendText(batch.joinToString("\n", postfix = "\n")) }
    }
}
