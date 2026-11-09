package fr.p308.lanceur.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import fr.p308.lanceur.optim.Optim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Réglages → Optimiser l'autoradio : mémoire, mode CarPlay, réglages Android guidés, applis inutiles. */
@Composable
fun OptimDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleanMsg by remember { mutableStateOf<String?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    val mem by produceState(Optim.mem(ctx)) { while (true) { delay(2000); value = Optim.mem(ctx) } }
    // Relu toutes les 2 s : les réglages se changent dans Android, puis on revient ici.
    val anim by produceState(Optim.animationScales(ctx)) { while (true) { delay(2000); value = Optim.animationScales(ctx) } }
    val scan by produceState(Optim.backgroundScanning(ctx)) { while (true) { delay(2000); value = Optim.backgroundScanning(ctx) } }
    val unused by produceState<List<Optim.Unused>?>(null) { value = withContext(Dispatchers.IO) { Optim.unusedApps(ctx) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 760.dp).padding(12.dp),
        containerColor = C.Card,
        title = { Text("Optimiser l'autoradio", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // --- Mémoire ---
                Text("Mémoire", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${mem.availMb} Mo libres sur ${mem.totalMb} Mo" + if (mem.low) " — mémoire basse !" else "",
                            fontSize = 14.sp, color = if (mem.low) C.Orange else C.Text)
                        LinearProgressIndicator(
                            progress = { 1f - mem.availMb.toFloat() / mem.totalMb.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = if (mem.availMb < mem.totalMb / 5) C.Orange else C.Green,
                        )
                    }
                    Spacer(Modifier.padding(6.dp))
                    Button(enabled = !cleaning, onClick = {
                        cleaning = true
                        scope.launch {
                            cleanMsg = withContext(Dispatchers.IO) { Optim.clean(ctx) }
                            cleaning = false
                        }
                    }) { Text(if (cleaning) "…" else "Libérer") }
                }
                Text(cleanMsg ?: "Ferme les applis qui traînent en arrière-plan (jamais celle à l'écran, la musique en cours, " +
                    "CarPlay ni les services de l'autoradio).", fontSize = 12.sp, color = if (cleanMsg != null) C.Green else C.Sub)

                Spacer(Modifier.height(10.dp))
                Toggle("Mode CarPlay", "Pendant CarPlay, le Lanceur lit l'OBD moins souvent (≈ 3 fois/s) et redessine son " +
                    "panneau 2 fois/s : plus de place pour le CarPlay sans fil (même puce Bluetooth / Wi-Fi).",
                    Optim.carplayLight, Optim::updateCarplayLight)
                Toggle("Libérer la mémoire à l'ouverture de CarPlay", "Au plus toutes les 10 minutes.",
                    Optim.autoClean, Optim::updateAutoClean)

                // --- Réglages Android guidés ---
                Spacer(Modifier.height(10.dp))
                Text("Réglages d'Android (à faire une fois)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val animOk = anim.all { it <= 0.5f }
                Step(
                    "Animations à 0,5×", animOk,
                    "Actuellement ${anim.joinToString(" / ") { "%.1f×".format(Locale.FRANCE, it) }}. Options pour les développeurs → " +
                        "« Échelle animation fenêtres », « Échelle animation transitions », « Échelle durée animation » : 0,5×. " +
                        "Tout s'ouvre 2 fois plus vite. Si ces options n'existent pas : Paramètres → À propos → toucher 7 fois " +
                        "« Numéro de build ».",
                ) { openSettings(ctx, *devSettingsIntents()) }
                val scanOn = scan.first == true || scan.second == true
                Step(
                    "Recherche Wi-Fi / Bluetooth permanente coupée", scan.first == false && scan.second != true,
                    (if (scan.first == null) "État inconnu. " else if (scanOn) "Encore active. " else "Coupée. ") +
                        "Localisation → Recherche (ou Analyse) : décoche la recherche Wi-Fi et Bluetooth. L'autoradio " +
                        "arrête de balayer les ondes en permanence, ce qui gêne le CarPlay sans fil.",
                ) { openSettings(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }
                val store = remember { ctx.packageManager.getLaunchIntentForPackage("com.android.vending") }
                if (store != null) {
                    Step(
                        "Mises à jour automatiques du Play Store", null,
                        "Play Store → ton profil → Paramètres → Préférences réseau → Mise à jour automatique des applis : " +
                            "« Ne pas mettre à jour automatiquement ». Sinon il télécharge en fond pendant que tu roules.",
                    ) { openSettings(ctx, store) }
                }
                Step(
                    "Démarrage rapide (veille de l'autoradio)", null,
                    "Réglages de l'autoradio → Réglages d'usine (code souvent 8888, 3368 ou 126) → option de veille après " +
                        "coupure du contact (« Sleep », « Veille » ou « ACC off » selon la version) : quelques heures. " +
                        "Au démarrage suivant, tout est prêt en 2 s au lieu de 30, et CarPlay se reconnecte plus vite.",
                    null,
                )

                // --- Applis inutiles ---
                Spacer(Modifier.height(10.dp))
                Text("Applis pas ouvertes depuis 30 jours", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                when (val list = unused) {
                    null -> Text("Autorise « Accès aux données d'utilisation » (Réglages → Ouvrir avec CarPlay → Autoriser) " +
                        "pour savoir lesquelles servent.", fontSize = 12.sp, color = C.Sub)
                    else -> {
                        Text(
                            if (list.isEmpty()) "Aucune : rien à désactiver." else
                                "Ouvrir → « Désactiver » (ou « Forcer l'arrêt » si Désactiver est grisé). Réversible au même " +
                                    "endroit. Les applis de l'autoradio (com.syu, FYT) ne sont pas proposées.",
                            fontSize = 12.sp, color = C.Sub,
                        )
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            list.forEach { a ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                        .background(C.Field.copy(0.4f)).padding(horizontal = 10.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(a.label, fontSize = 14.sp)
                                        Text((a.lastUsedDays?.let { "Ouverte il y a $it j" } ?: "Jamais ouverte") +
                                            (if (a.system) " · appli d'origine" else ""), fontSize = 11.sp, color = C.Sub)
                                    }
                                    TextButton(onClick = {
                                        openSettings(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                                    }) { Text("Ouvrir") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, fontSize = 12.sp, color = C.Sub)
        }
        Switch(value, onChange)
    }
}

/** Une étape guidée : ✓ vert si c'est fait (quand on peut le lire), sinon •, et un bouton qui ouvre le bon écran. */
@Composable
private fun Step(title: String, done: Boolean?, text: String, open: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (done == true) "✓" else "•", fontSize = 16.sp, color = if (done == true) C.Green else if (done == false) C.Orange else C.Sub)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (done == true) C.Green else Color.Unspecified)
            Text(text, fontSize = 12.sp, color = C.Sub)
        }
        if (open != null) TextButton(onClick = open) { Text("Ouvrir") }
    }
}
