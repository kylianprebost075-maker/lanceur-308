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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
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
        // v0.29 : l'égaliseur de l'autoradio n'a pas de retard par haut-parleur (relevé du 11/11) : on règle la croix
        // « Champ » (balance / fader) d'après les distances, puis on finit à l'oreille.
        Tile(Modifier.weight(1.2f).fillMaxHeight()) {
            val ctx = LocalContext.current
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SeatModeSection()
                Spacer(Modifier.height(10.dp))
                Text("Réglage à faire dans l'autoradio", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val plan = fr.p308.lanceur.sound.FytStage.plan(SoundPrefs.distancesCm.toList())
                if (plan == null) {
                    Text("Saisis les distances des deux tweeters avant (gauche et droit).", fontSize = 14.sp, color = C.Sub)
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StageCross(plan.rightSteps, plan.frontSteps, Modifier.size(120.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            val side = if (plan.rightSteps >= 0) "→ vers la droite" else "← vers la gauche"
                            Text("${kotlin.math.abs(plan.rightSteps)} cran(s) $side", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("${plan.frontSteps} cran(s) ↑ vers l'avant", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Le tweeter ${if (plan.nearLeft) "gauche" else "droit"} est ${plan.gapCm} cm plus près : il paraît " +
                                    "${f1(plan.louderDb)} dB plus fort et tire la voix vers lui.",
                                fontSize = 12.sp, color = C.Sub,
                            )
                        }
                    }
                    Text(
                        "1. Ouvre l'égaliseur, onglet « Champ » (en bas à droite).\n" +
                            "2. Appuie sur « Tous » : le point revient au centre.\n" +
                            "3. Appuie ${kotlin.math.abs(plan.rightSteps)} fois sur ${if (plan.rightSteps >= 0) "→" else "←"}, " +
                            "puis ${plan.frontSteps} fois sur ↑.\n" +
                            "4. Lance le bruit rose : il doit sembler venir du milieu du tableau de bord. S'il penche encore " +
                            "d'un côté, un cran de plus vers l'autre côté.",
                        fontSize = 13.sp,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val i = ctx.packageManager.getLaunchIntentForPackage("com.syu.eq")
                            if (i != null) ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            else android.widget.Toast.makeText(ctx, "Égaliseur du fabricant introuvable sur cet autoradio", android.widget.Toast.LENGTH_LONG).show()
                        }, modifier = Modifier.weight(1f)) { Text("Ouvrir l'égaliseur") }
                        PlayButton("Bruit rose (test)", Modifier.weight(1f)) { SoundGen.play(ctx, SoundGen.Pink(Side.BOTH)) }
                    }
                    PlayingBar()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cet autoradio ne règle pas de retard par haut-parleur : la balance est la seule compensation possible. " +
                            "Le nombre de crans est une estimation (≈ ${f1(fr.p308.lanceur.sound.FytStage.DB_PER_STEP)} dB par cran) : " +
                            "c'est l'écoute qui décide. « Mode de conduite » fait 4 crans à gauche : à l'inverse de ce qu'il faut " +
                            "pour une conduite à gauche (il pousse encore la voix vers ta portière).",
                        fontSize = 11.sp, color = C.Sub,
                    )
                }
            }
        }
    }
}

/**
 * v0.31 : balance « Seul » (son centré sur le conducteur, d'après les distances) ou « Passagers » (centre : équilibré
 * pour tout le monde). Le Lanceur règle l'égaliseur FYT à ta place par le service d'accessibilité.
 */
@Composable
private fun SeatModeSection() {
    val ctx = LocalContext.current
    val status by fr.p308.lanceur.sound.ChampService.status.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    val enabled = remember(msg) { fr.p308.lanceur.sound.ChampService.enabled(ctx) }
    Text("Son pour qui ?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Segmented(fr.p308.lanceur.sound.SeatMode.entries.map { it to it.label }, SoundPrefs.seatMode) { m ->
        SoundPrefs.updateSeatMode(m)
        msg = fr.p308.lanceur.sound.ChampService.apply(ctx, m)
    }
    Text(
        if (SoundPrefs.seatMode == fr.p308.lanceur.sound.SeatMode.SOLO) "Seul : la voix vient du milieu du tableau de bord, pour toi."
        else "Passagers : balance au centre, chacun entend les deux côtés.",
        fontSize = 12.sp, color = C.Sub,
    )
    (status.ifEmpty { null } ?: msg)?.let { Text(it, fontSize = 13.sp, color = if (enabled) C.Green else C.Orange) }
    val eqApp = remember { ctx.packageManager.getLaunchIntentForPackage(fr.p308.lanceur.sound.ChampService.EQ_PKG) != null }
    if (!enabled && eqApp) {
        TextButton(onClick = { fr.p308.lanceur.sound.ChampService.openSettings(ctx) }) {
            Text("Activer le réglage automatique (Accessibilité)")
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
                BassSection()
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

/**
 * Croix « Champ » de l'égaliseur FYT, dessinée comme sur l'autoradio (8 crans de chaque côté) avec le point à viser.
 */
@Composable
private fun StageCross(right: Int, front: Int, modifier: Modifier = Modifier) {
    val line = C.Sub
    val dot = C.Accent
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF07090F))) {
        val n = fr.p308.lanceur.sound.FytStage.MAX_STEPS
        val step = size.width / (2 * n + 2)
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        for (k in listOf(-n / 2, n / 2)) {
            drawLine(line.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(c.x + k * step, 0f), androidx.compose.ui.geometry.Offset(c.x + k * step, size.height))
            drawLine(line.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(0f, c.y + k * step), androidx.compose.ui.geometry.Offset(size.width, c.y + k * step))
        }
        drawLine(line, androidx.compose.ui.geometry.Offset(c.x, 0f), androidx.compose.ui.geometry.Offset(c.x, size.height), strokeWidth = 2f)
        drawLine(line, androidx.compose.ui.geometry.Offset(0f, c.y), androidx.compose.ui.geometry.Offset(size.width, c.y), strokeWidth = 2f)
        drawCircle(dot, radius = step * 0.9f, center = androidx.compose.ui.geometry.Offset(c.x + right * step, c.y - front * step))
    }
}

/**
 * v0.30 : basses selon le volume. 1) trouver la fréquence qui fait vibrer les portes (montée lente 30 → 150 Hz, on
 * touche « Ça vibre »), 2) l'appli monte les basses à volume bas et baisse cette fréquence à volume fort.
 */
@Composable
private fun ColumnScope.BassSection() {
    val ctx = LocalContext.current
    val status by fr.p308.lanceur.sound.BassAuto.status.collectAsStateWithLifecycle()
    val gains by fr.p308.lanceur.sound.BassAuto.gains.collectAsStateWithLifecycle()
    val play by SoundGen.state.collectAsStateWithLifecycle()
    var sweep by remember { mutableStateOf<fr.p308.lanceur.sound.ResonanceSweep?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Basses automatiques", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Plus de basses quand le volume est bas ; à volume fort, la fréquence qui fait vibrer les portes est " +
                    "baissée. Agit sur la musique et CarPlay (pas sur la radio FM).",
                fontSize = 12.sp, color = C.Sub,
            )
        }
        Switch(SoundPrefs.bassAuto, SoundPrefs::updateBassAuto)
    }
    if (SoundPrefs.bassAuto) {
        Segmented(listOf(1 to "Doux", 2 to "Moyen", 3 to "Fort"), SoundPrefs.bassStrength) { SoundPrefs.updateBassStrength(it) }
        Text(
            status.label + if (gains.isNotEmpty()) " : " + gains.filter { kotlin.math.abs(it.second) >= 0.1 }
                .joinToString(", ") { "${it.first} Hz ${if (it.second > 0) "+" else ""}${f1(it.second)} dB" }.ifEmpty { "son neutre à ce volume" } else "",
            fontSize = 12.sp, color = if (status == fr.p308.lanceur.sound.BassAuto.Status.UNSUPPORTED) C.Orange else C.Sub,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Vibration des portes : " + if (SoundPrefs.resonanceHz > 0) "${SoundPrefs.resonanceHz} Hz" else "pas encore trouvée",
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
    Text(
        "Monte le volume comme quand ça vibre, lance la montée des basses (30 s), et touche « Ça vibre » dès que la porte " +
            "se met à vibrer.",
        fontSize = 12.sp, color = C.Sub,
    )
    val s = sweep
    val sweeping = play.playing && s != null
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!sweeping) {
            Button(onClick = {
                val sig = fr.p308.lanceur.sound.ResonanceSweep()
                sweep = sig
                SoundGen.play(ctx, sig)
            }, modifier = Modifier.weight(1f)) { Text("Lancer la montée des basses") }
            if (SoundPrefs.resonanceHz > 0) {
                TextButton(onClick = { SoundPrefs.updateResonance(0) }) { Text("Effacer") }
            }
        } else {
            Button(
                onClick = { SoundPrefs.updateResonance(s!!.currentHz.toInt()); SoundGen.stop(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = C.Orange), modifier = Modifier.weight(1f),
            ) { Text("Ça vibre ! (${play.detail})") }
            TextButton(onClick = { SoundGen.stop(ctx) }) { Text("Arrêter") }
        }
    }
}