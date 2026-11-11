package fr.p308.lanceur.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.util.zip.ZipFile

data class CanProbeState(
    val running: Boolean = false,
    val packages: List<String> = emptyList(),
    val listenedActions: Int = 0,
    val events: List<String> = emptyList(),
    val reportFile: String? = null,
)

/**
 * Cherche, sans ADB, comment l'autoradio transmet l'état des ouvrants : pendant qu'on ouvre une porte,
 * on écoute les messages (broadcasts) des applis CAN et on surveille réglages et propriétés système.
 */
object CanProbe {
    private val _state = MutableStateFlow(CanProbeState())
    val state: StateFlow<CanProbeState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var fyt: FytBridge? = null
    private val fytValues = HashMap<String, String>()
    private var fytQuietUntil = 0L
    private val KEYWORDS = Regex("can|car|mcu|syu|fyt|microntek|hct|hiworld|raise|xinpu|binary|simple|vehicle|bus|door|radar|backcar|ts\\.|nwd|kld|joying", RegexOption.IGNORE_CASE)
    private val ACTION = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")

    private fun event(text: String) = _state.update { it.copy(events = it.events + "${LocalTime.now().withNano(0)}  $text") }

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        _state.value = CanProbeState(running = true)
        job = scope.launch {
            val pm = app.packageManager
            val pkgs = pm.getInstalledPackages(PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
                .filter { it.packageName != app.packageName && KEYWORDS.containsMatchIn(it.packageName) }
            _state.update { it.copy(packages = pkgs.map { p -> p.packageName }) }
            pkgs.forEach { p ->
                event("APPLI ${p.packageName}")
                p.services?.filter { it.exported }?.forEach { event("   service exporté : ${it.name}") }
                p.providers?.filter { it.exported }?.forEach { event("   fournisseur : ${it.authority}") }
            }
            // Noms de messages candidats, extraits des fichiers des applis CAN (comme la commande « strings »).
            val actions = mutableSetOf<String>()
            pkgs.forEach { p -> p.applicationInfo?.sourceDir?.let { dir -> runCatching { actions += actionStrings(File(dir)) } } }
            actions += listOf("android.intent.action.CLOSE_SYSTEM_DIALOGS")
            val filter = IntentFilter().apply { actions.take(1000).forEach { addAction(it) } }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val extras = i.extras?.keySet()?.joinToString { k -> "$k=${i.extras?.get(k).let { v -> if (v is ByteArray) v.joinToString(" ") { "%02X".format(it) } else v.toString() }}" }
                    event("MESSAGE ${i.action}  ${extras ?: ""}")
                }
            }
            app.registerReceiver(receiver, filter)
            _state.update { it.copy(listenedActions = minOf(actions.size, 1000)) }
            event("Écoute de ${minOf(actions.size, 1000)} messages. Ouvre puis ferme la porte conducteur, puis le coffre.")

            // Autoradios FYT : abonnement direct au service interne. Les valeurs initiales sont mémorisées
            // en silence (3 s), sauf les infos clés (profil CAN 1000, version 1005, phares canal 4).
            fytQuietUntil = System.currentTimeMillis() + 4000
            fytValues.clear()
            val probeCodes = (0..300) + (1000..1030)
            fyt = FytBridge(onUpdate = { m, code, ints, flts, strs ->
                val key = "module $m canal $code"
                val v = listOfNotNull(ints?.joinToString(",")?.let { "i=[$it]" }, flts?.joinToString(",")?.let { "f=[$it]" },
                    strs?.joinToString(",")?.let { "s=[$it]" }).joinToString(" ")
                val old = synchronized(fytValues) { fytValues.put(key, v) }
                val importantInit = (m == 7 && code in listOf(1000, 1005)) || (m == 0 && code in listOf(4, 12))
                if ((System.currentTimeMillis() > fytQuietUntil || importantInit) && old != v) event("FYT $key : $old → $v")
            }, log = ::event).also { it.connect(app, 0..15, probeCodes) }

            var before = snapshot(app)
            while (isActive) {
                delay(700)
                val now = snapshot(app)
                (before.keys + now.keys).forEach { k -> if (before[k] != now[k]) event("CHANGEMENT $k : ${before[k]} → ${now[k]}") }
                before = now
            }
        }
    }

    fun stop(ctx: Context) {
        job?.cancel()
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
        fyt?.disconnect(ctx)
        fyt = null
        event("FYT : ${fytValues.size} canaux avaient une valeur")
        // v0.33 : valeurs finales du module CAN (7) qui ne sont pas nulles, même si elles n'ont pas changé pendant
        // l'écoute : c'est là que se trouvent l'autonomie, la consommation et les autres chiffres de l'ordinateur de bord.
        synchronized(fytValues) {
            fytValues.filter { it.key.startsWith("module 7 ") && !Regex("^i=\\[0(,0)*]$").matches(it.value) && it.value.isNotBlank() }
                .toSortedMap(compareBy { it.substringAfter("canal ").toIntOrNull() ?: 0 })
                .forEach { (k, v) -> event("VALEUR FYT $k = $v") }
        }
        val file = File(ctx.getExternalFilesDir(null), "diagnostic-can.txt")
        writeSynced(file, _state.value.events.joinToString("\n").toByteArray())
        _state.update { it.copy(running = false, reportFile = file.absolutePath) }
    }

    /**
     * Copie sur la clé USB les APK des applis CAN (com.syu.ms, com.syu.canbus…) et le rapport, avec fsync
     * pour éviter un fichier de 0 octet si la clé USB est retirée rapidement.
     */
    fun copyApks(ctx: Context): String {
        val usb = fr.p308.lanceur.backup.UsbStorage.keys(ctx)
        if (usb.isEmpty()) return fr.p308.lanceur.backup.UsbStorage.missingReason(ctx)
        val reportBytes = if (_state.value.events.isNotEmpty()) {
            _state.value.events.joinToString("\n").toByteArray()
        } else {
            File(ctx.getExternalFilesDir(null), "diagnostic-can.txt").takeIf { it.exists() }?.readBytes()
        }
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledPackages(0).filter { it.packageName.startsWith("com.syu.") || it.packageName.startsWith("com.fyt.") || KEYWORDS.containsMatchIn(it.packageName) }
        var n = 0
        usb.forEach { dir ->
            reportBytes?.let { writeSynced(File(dir, "diagnostic-can.txt"), it) }
            val out = File(dir, "apk-can").apply { mkdirs() }
            pkgs.forEach { p ->
                p.applicationInfo?.sourceDir?.let { src -> if (copySynced(File(src), File(out, "${p.packageName}.apk"))) n++ }
            }
        }
        return "$n APK et rapport copiés sur la clé (attends 3 s avant de retirer la clé)"
    }

    private fun writeSynced(dst: File, bytes: ByteArray): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        java.io.FileOutputStream(dst).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
    }.isSuccess

    private fun copySynced(src: File, dst: File): Boolean = runCatching {
        src.inputStream().use { input ->
            java.io.FileOutputStream(dst).use { out ->
                input.copyTo(out)
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
    }.isSuccess

    /** Réglages système/global/secure et propriétés système lisibles par une appli. */
    private fun snapshot(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        for (table in listOf("system", "global", "secure")) runCatching {
            ctx.contentResolver.query(Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) out["$table/${c.getString(0)}"] = c.getString(1) ?: ""
            }
        }
        runCatching {
            Runtime.getRuntime().exec("getprop").inputStream.bufferedReader().forEachLine { l ->
                val m = Regex("^\\[(.+?)]: \\[(.*)]$").find(l) ?: return@forEachLine
                val k = m.groupValues[1]
                if (!k.startsWith("ro.") && !k.contains("time", true)) out["prop/$k"] = m.groupValues[2]
            }
        }
        // Valeurs qui bougent toutes seules : on les ignore.
        return out.filterKeys { !it.contains("screen_brightness") && !it.contains("next_alarm") && !it.contains("boot_count") }
    }

    private fun actionStrings(apk: File): Set<String> {
        val found = HashSet<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { e ->
                val bytes = zip.getInputStream(e).readBytes()
                val sb = StringBuilder()
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    if (c in 0x20..0x7E) sb.append(c.toChar()) else {
                        if (sb.length in 12..90) sb.toString().let { s -> if (ACTION.matches(s) && KEYWORDS.containsMatchIn(s) && !s.startsWith("java") && !s.startsWith("android.support")) found += s }
                        sb.setLength(0)
                    }
                }
            }
        }
        return found
    }
}
