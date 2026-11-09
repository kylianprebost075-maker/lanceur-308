package fr.p308.lanceur.ui

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.p308.lanceur.sound.FytSoundApps
import fr.p308.lanceur.sound.Side
import fr.p308.lanceur.sound.SoundGen
import fr.p308.lanceur.sound.SoundPrefs
import fr.p308.lanceur.sound.Speaker
import fr.p308.lanceur.sound.TimeAlignment
import fr.p308.lanceur.sound.Voice
import java.util.Locale

private enum class SoundTab(val label: String) {
    ALIGN("Alignement"), TESTS("Tests"), PC("Mesure au PC"), OPTIONS("Options"),
}

private fun f1(v: Double) = "%.1f".format(Locale.FRANCE, v)
private fun f2(v: Double) = "%.2f".format(Locale.FRANCE, v)

/** Onglet Son : alignement temporel, tests des haut-parleurs, signaux pour la mesure au PC, volume et annonces. */
@Composable
fun SoundScreen() {
    var tab by rememberSaveable { mutableStateOf(SoundTab.ALIGN) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented(SoundTab.entries.map { it to it.label }, tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                SoundTab.ALIGN -> AlignPanel()
                SoundTab.TESTS -> TestsPanel()
                SoundTab.PC -> PcPanel()
                SoundTab.OPTIONS -> OptionsPanel()
            }
        }
    }
}

@Composable
private fun AlignPanel() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Distances", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Assis en position de conduite, mesure au mètre ruban du milieu de ta tête (entre les oreilles) " +
                        "au centre de chaque haut-parleur. À l'avant : le tweeter sous le pare-brise.",
                    fontSize = 12.sp, color = C.Sub,
                )
                Spacer(Modifier.height(6.dp))
                Speaker.entries.forEach { s -> DistanceField(s) }
            }
        }
        Tile(Modifier.weight(1.2f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Valeurs à recopier dans l'autoradio", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val res = TimeAlignment.compute(SoundPrefs.distancesCm.toList())
                if (res == null) {
                    Text("Saisis au moins deux distances.", fontSize = 14.sp, color = C.Sub)
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        listOf("Haut-parleur" to 1.3f, "Retard" to 1f, "ou en cm" to 0.8f, "Niveau" to 0.9f).forEach { (t, w) ->
                            Text(t, fontSize = 12.sp, color = C.Sub, modifier = Modifier.weight(w))
                        }
                    }
                    res.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                                .background(C.Field.copy(0.5f)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.speaker.short, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                            Text("${f2(r.delayMs)} ms", fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Text("${r.delayCm} cm", fontSize = 15.sp, color = C.Sub, modifier = Modifier.weight(0.8f))
                            Text(if (r.levelDb > -0.25) "0 dB" else "${f1(r.levelDb)} dB", fontSize = 17.sp,
                                color = if (r.levelDb < -0.25) C.Orange else C.Text, modifier = Modifier.weight(0.9f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Dans l'autoradio (Réglages → Son / DSP → alignement temporel ou « Delay »), mets chaque retard : " +
                            "en millisecondes, ou en centimètres si l'écran compte en cm. Le plus loin reste à 0. " +
                            "Niveau : baisse de ce nombre de dB les haut-parleurs les plus proches (si l'autoradio le permet " +
                            "par voie ; sinon, la balance vers la droite fait à peu près la même chose à l'avant).",
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Les woofers Focal des portes passent par le même canal que leur tweeter : on règle sur le tweeter, " +
                            "c'est lui qui place la scène. Vérification ensuite au micro du PC (onglet Mesure au PC).",
                        fontSize = 11.sp, color = C.Sub,
                    )
                }
            }
        }
    }
}

@Composable
private fun DistanceField(s: Speaker) {
    var text by remember(s) { mutableStateOf(SoundPrefs.distancesCm[s.ordinal].takeIf { it > 0 }?.toString() ?: "") }
    OutlinedTextField(
        text,
        { v ->
            text = v.filter(Char::isDigit).take(3)
            SoundPrefs.setDistance(s, text.toIntOrNull() ?: 0)
        },
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        singleLine = true,
        label = { Text("${s.label} (cm)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Bandeau du son en cours + bouton Arrêter. */
@Composable
private fun ColumnScope.PlayingBar() {
    val ctx = LocalContext.current
    val st by SoundGen.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (st.playing) C.Accent.copy(0.25f) else C.Field.copy(0.4f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (st.playing) "▶ ${st.title}" else "Aucun son en cours", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (st.playing && st.detail.isNotEmpty()) Text(st.detail, fontSize = 13.sp, color = C.Accent)
        }
        if (st.playing) {
            Button(onClick = { SoundGen.stop(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = C.Red)) { Text("Arrêter") }
        }
    }
}

@Composable
private fun PlayButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun TestsPanel() {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Chaque haut-parleur", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Avant ou arrière : règle le fader de l'autoradio (tout à l'avant, puis tout à l'arrière).",
                    fontSize = 12.sp, color = C.Sub)
                Spacer(Modifier.height(6.dp))
                Text("Son pur 1 000 Hz (aigus et médiums)", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Side.entries.forEach { s -> PlayButton(s.label, Modifier.weight(1f)) { SoundGen.play(ctx, SoundGen.Tone(1000.0, s)) } }
                }
                Text("Basses 60 Hz", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Side.entries.forEach { s -> PlayButton(s.label, Modifier.weight(1f)) { SoundGen.play(ctx, SoundGen.Tone(60.0, s)) } }
                }
                Text("Balayage 20 Hz → 20 kHz (bruits parasites, vibrations de garnitures)", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Side.entries.forEach { s -> PlayButton(s.label, Modifier.weight(1f)) { SoundGen.play(ctx, SoundGen.Sweep(s)) } }
                }
                Spacer(Modifier.height(4.dp))
                Text("Commence à volume bas. Un haut-parleur muet ou qui grésille : vérifie son branchement.",
                    fontSize = 11.sp, color = C.Sub)
            }
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Polarité (+ et − inversés ?)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Des basses alternent toutes les 4 s : A (gauche et droite en phase) puis B (droite inversée). " +
                        "Assis à ta place :",
                    fontSize = 13.sp,
                )
                Text("• A plus plein, voix au centre : branchement correct.", fontSize = 13.sp, color = C.Green)
                Text("• B plus plein : un haut-parleur est branché à l'envers. Inverse les 2 fils de l'un d'eux.",
                    fontSize = 13.sp, color = C.Orange)
                Text("Fais le test fader à l'avant (Focal), puis à l'arrière (Pioneer).", fontSize = 12.sp, color = C.Sub)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { SoundGen.play(ctx, SoundGen.Polarity()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Lancer le test A / B")
                }
                Spacer(Modifier.weight(1f))
                PlayingBar()
            }
        }
    }
}

@Composable
private fun PcPanel() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var from by remember { mutableIntStateOf((am.getStreamVolume(AudioManager.STREAM_MUSIC)).coerceIn(0, max)) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1.1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Mesure avec le micro du PC", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Pose le PC ouvert sur le siège conducteur, le micro à hauteur de tes oreilles, vitres et portes " +
                        "fermées, moteur coupé. Sur le PC, lance « mesure_son.bat » (dossier outils\\son) et choisis la " +
                        "même mesure ; il te dit quand toucher le bouton ici.",
                    fontSize = 12.sp, color = C.Sub,
                )
                Spacer(Modifier.height(6.dp))
                Text("1. Courbe de réponse (égaliseur)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Side.entries.forEach { s -> PlayButton("Bruit rose ${s.label.lowercase()}", Modifier.weight(1f)) { SoundGen.play(ctx, SoundGen.Pink(s)) } }
                }
                Text("2. Vérification de l'alignement (fader à l'avant, puis à l'arrière)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                PlayButton("Clics gauche / droite", Modifier.fillMaxWidth()) { SoundGen.play(ctx, SoundGen.Clicks()) }
                Text("3. Volume maximum sans saturer (ampli)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Départ au cran", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { from = (from - 1).coerceAtLeast(0) }) { Text("−", fontSize = 20.sp) }
                    Text("$from / $max", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { from = (from + 1).coerceAtMost(max) }) { Text("+", fontSize = 20.sp) }
                }
                PlayButton("Lancer la montée en volume", Modifier.fillMaxWidth()) { SoundGen.play(ctx, SoundGen.Saturation(ctx, from)) }
                Text(
                    "Le volume monte d'un cran toutes les 3 s jusqu'au maximum : sors de la voiture ou bouche-toi les " +
                        "oreilles, touche Arrêter au moindre doute. Ton volume d'avant est remis à la fin.",
                    fontSize = 11.sp, color = C.Orange,
                )
            }
        }
        Tile(Modifier.weight(0.9f).fillMaxHeight()) {
            Text("Ce que dit le PC", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "• Courbe : les bandes en trop ou en manque, et les réglages d'égaliseur proposés.\n" +
                    "• Clics : l'écart réel entre gauche et droite ; s'il reste un décalage, il propose une correction " +
                    "du retard.\n" +
                    "• Montée en volume : la distorsion à chaque cran, et le cran à ne pas dépasser.",
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            PlayingBar()
        }
    }
}

@Composable
private fun OptionsPanel() {
    val ctx = LocalContext.current
    val voice by Voice.status.collectAsStateWithLifecycle()
    var fytMsg by remember { mutableStateOf<String?>(null) }
    var fytApps by remember { mutableStateOf<List<FytSoundApps.AppFile>?>(null) }
    LaunchedEffect(Unit) { Voice.init(ctx) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Volume selon la vitesse", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Monte un peu à 50, 80 et 110 km/h, redescend en ville. Ton réglage à la main reste la base.",
                            fontSize = 12.sp, color = C.Sub)
                    }
                    Switch(SoundPrefs.speedVolume, SoundPrefs::updateSpeedVolume)
                }
                if (SoundPrefs.speedVolume) {
                    Segmented(listOf(1 to "Léger", 2 to "Moyen", 3 to "Fort"), SoundPrefs.speedStrength) { SoundPrefs.updateSpeedStrength(it) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Annonces vocales", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Turbo refroidi, régénération du FAP, réserve, surchauffe, batterie. La musique baisse le temps " +
                                "de la phrase.",
                            fontSize = 12.sp, color = C.Sub,
                        )
                    }
                    Switch(SoundPrefs.voice, SoundPrefs::updateVoice)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (voice) {
                            Voice.Status.READY -> "Voix française prête"
                            Voice.Status.NO_FRENCH -> "Pas de voix française sur l'autoradio (Android → Langues → Synthèse vocale)"
                            else -> "Démarrage de la voix…"
                        },
                        fontSize = 12.sp, color = if (voice == Voice.Status.NO_FRENCH) C.Orange else C.Sub, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { Voice.say("Turbo refroidi, tu peux couper le moteur.", force = true) },
                        enabled = voice == Voice.Status.READY) { Text("Essayer") }
                }
            }
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Piloter le DSP de l'autoradio", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Pour qu'un jour le Lanceur envoie lui-même retards et égaliseur, il faut d'abord étudier l'appli son " +
                        "de FYT. Ce bouton COPIE seulement ses fichiers sur la clé USB (rien n'est modifié sur l'autoradio).",
                    fontSize = 12.sp, color = C.Sub,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { fytApps = FytSoundApps.list(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chercher les applis son")
                }
                fytApps?.let { apps ->
                    Text(if (apps.isEmpty()) "Aucune trouvée." else apps.joinToString("\n") { "• ${it.label} (${it.pkg})" },
                        fontSize = 12.sp)
                    if (apps.isNotEmpty()) {
                        Button(onClick = { fytMsg = FytSoundApps.copyToUsb(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copier sur la clé USB")
                        }
                    }
                }
                fytMsg?.let { Text(it, fontSize = 13.sp, color = C.Green) }
            }
        }
    }
}
