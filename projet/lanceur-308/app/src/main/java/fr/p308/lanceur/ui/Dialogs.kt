package fr.p308.lanceur.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import fr.p308.lanceur.CAR_COLORS
import fr.p308.lanceur.backup.Backup
import fr.p308.lanceur.HomeAlias
import fr.p308.lanceur.Slot
import fr.p308.lanceur.UiPrefs
import fr.p308.lanceur.launchPackage
import fr.p308.lanceur.maintenance.Maintenance
import fr.p308.lanceur.maintenance.MaintenanceItem
import fr.p308.lanceur.maintenance.fmtKm
import fr.p308.lanceur.vehicle.Vehicle
import fr.p308.lanceur.vehicle.odometerOrigin
import fr.p308.lanceur.vehicle.odometerText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun digits(s: String) = s.filter(Char::isDigit).take(7)

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false, placeholder: String? = null) {
    OutlinedTextField(
        value, onChange, modifier, singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

/**
 * Sur grand écran, Android ouvre le clavier dès qu'un champ reçoit le focus à l'ouverture d'une fenêtre :
 * il masquerait tout. On ne l'ouvre que quand on touche un champ. À appeler dans le contenu de la fenêtre.
 */
@Composable
fun NoAutoKeyboardInDialog() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window
            ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }
    // Avant Android 9, la fenêtre donne d'office le focus au premier élément focalisable :
    // cet élément invisible (1 px, Compose ignore les éléments de taille nulle) le prend à la place du premier champ.
    Box(Modifier.size(1.dp).focusable())
    // Sécurité si le focus arrive malgré tout sur un champ pendant l'animation d'ouverture.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(250)
        focus.clearFocus()
        keyboard?.hide()
    }
}

@Composable
fun MaintenanceDialog(initial: MaintenanceItem?, currentKm: Int?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var date by remember { mutableStateOf(initial?.lastDate?.format(DATE_FR) ?: "") }
    var km by remember { mutableStateOf(initial?.lastKm?.toString() ?: "") }
    var everyKm by remember { mutableStateOf((initial?.everyKm ?: 0).toString()) }
    var everyMonths by remember { mutableStateOf((initial?.everyMonths ?: 12).toString()) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val parsedDate = if (date.isBlank()) null else runCatching { LocalDate.parse(date.trim(), DATE_FR) }.getOrNull()
        when {
            name.isBlank() -> error = "Donne un nom à l'entretien."
            date.isNotBlank() && parsedDate == null -> error = "Date au format jj/mm/aaaa."
            else -> {
                val base = initial ?: MaintenanceItem(name = name.trim())
                Maintenance.upsert(base.copy(
                    name = name.trim(), lastDate = parsedDate, lastKm = km.toIntOrNull(),
                    everyKm = everyKm.toIntOrNull() ?: 0, everyMonths = everyMonths.toIntOrNull() ?: 0, note = note.trim(),
                ))
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 680.dp).padding(16.dp),
        containerColor = C.Card,
        title = {
            Column {
                Text(if (initial == null) "Nouvel entretien" else initial.name, fontWeight = FontWeight.Bold)
                Text("Laisse un intervalle à 0 s'il ne s'applique pas.", fontSize = 13.sp, color = C.Sub)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoAutoKeyboardInDialog()
                if (initial == null) Field(name, { name = it }, "Nom", Modifier.fillMaxWidth(), placeholder = "Ex : pneus avant, amortisseurs, recharge clim")
                Button(
                    onClick = {
                        date = LocalDate.now().format(DATE_FR)
                        currentKm?.let { km = it.toString() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = C.Green, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Fait aujourd'hui" + (currentKm?.let { " à ${fmtKm(it)} km" } ?: ""), fontWeight = FontWeight.SemiBold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(date, { date = it }, "Fait le (jj/mm/aaaa)", Modifier.weight(1f))
                    Field(km, { km = digits(it) }, "À (km)", Modifier.weight(1f), number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(everyKm, { everyKm = digits(it) }, "Tous les (km, 0 = aucun)", Modifier.weight(1f), number = true)
                    Field(everyMonths, { everyMonths = digits(it) }, "Ou tous les (mois)", Modifier.weight(1f), number = true)
                }
                Field(note, { note = it }, "Note (pièce, garage, prix…)", Modifier.fillMaxWidth())
                error?.let { Text(it, color = C.Red) }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text("Enregistrer") } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { Maintenance.delete(initial.id); onDismiss() }) { Text("Supprimer", color = C.Red) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

@Composable
fun OdometerDialog(currentKm: Int?, onDismiss: () -> Unit) {
    var km by remember { mutableStateOf(currentKm?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Kilométrage actuel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoAutoKeyboardInDialog()
                Text("Recopie le compteur. Ensuite, le GPS ajoute les kilomètres parcourus (tant que le CAN ne fournit pas le kilométrage).",
                    fontSize = 13.sp, color = C.Sub)
                Field(km, { km = digits(it) }, "Kilomètres", Modifier.fillMaxWidth(), number = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { km.toIntOrNull()?.let(Vehicle::setOdometer); onDismiss() }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun SettingRow(title: String, sub: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            sub?.let { Text(it, fontSize = 12.sp, color = C.Sub) }
        }
        trailing()
    }
}

/** v0.23 : écran de bienvenue, affiché une seule fois au premier lancement (pour ceux qui découvrent l'appli). */
@Composable
fun WelcomeDialog(onSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text("Bienvenue dans Lanceur 308", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Un écran d'accueil pour autoradio Android, réglé pour : ${fr.p308.lanceur.vehicle.CarProfile.NAME} ${fr.p308.lanceur.vehicle.CarProfile.ENGINE_LABEL}.", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Pour tout voir bouger sans rien brancher : « Voir la démo ».",
                    "Ta plaque sur la voiture : Réglages → Plaque.",
                    "Données du moteur : un boîtier OBD Bluetooth (ELM327, Vgate vLinker, iCar), puis l'onglet Moteur.",
                    "Portes et phares en direct : seulement sur les autoradios de marque FYT.",
                    "Garder cet écran au démarrage : Réglages → Utiliser comme écran d'accueil.",
                ).forEachIndexed { i, line -> Text("${i + 1}. $line", fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                Spacer(Modifier.height(8.dp))
                Text("L'appli n'a pas accès à Internet : rien ne sort de ton autoradio.", fontSize = 12.sp, color = C.Sub)
            }
        },
        confirmButton = { Button(onClick = { UiPrefs.markWelcomed() }) { Text("Commencer") } },
        dismissButton = {
            Row {
                TextButton(onClick = { UiPrefs.markWelcomed(); onSettings() }) { Text("Réglages") }
                TextButton(onClick = { Vehicle.updateDemo(true); UiPrefs.markWelcomed() }) { Text("Voir la démo") }
            }
        },
    )
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var isHome by remember { mutableStateOf(HomeAlias.isEnabled(ctx)) }
    var editingKm by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var optim by remember { mutableStateOf(false) }
    var outsideTemp by remember { mutableStateOf(false) }
    var devMessage by remember { mutableStateOf<String?>(null) }
    // Lus une seule fois à l'ouverture (ce sont des commandes : pas à chaque rafraîchissement de l'écran).
    var wifiAdbOn by remember { mutableStateOf(DevOptions.wifiAdbPort() != null) }
    var wifiAdbMessage by remember { mutableStateOf(DevOptions.wifiAdbStatus()) }
    var wifiAdbBusy by remember { mutableStateOf(false) }
    var plateText by remember { mutableStateOf(UiPrefs.plate) }
    var plateMessage by remember { mutableStateOf<String?>(null) }
    var plateBusy by remember { mutableStateOf(false) }
    val plateImage = remember(plateText) { PlateModel.bitmap(plateText.ifEmpty { PlateModel.EXAMPLE }).asImageBitmap() }
    val vs by Vehicle.state.collectAsStateValue()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 680.dp).padding(16.dp),
        containerColor = C.Card,
        title = { Text("Réglages", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Couleur : ${UiPrefs.carColor.name}", fontSize = 16.sp)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CAR_COLORS.forEachIndexed { i, c ->
                        Spacer(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(c.argb))
                                .border(if (i == UiPrefs.colorIndex) 3.dp else 1.dp, if (i == UiPrefs.colorIndex) C.Accent else C.Sub, CircleShape)
                                .clickable { UiPrefs.setColor(i) }
                        )
                    }
                }
                SettingRow("5 portes", "Décoche pour une 308 3 portes") {
                    Switch(UiPrefs.fiveDoors, UiPrefs::updateFiveDoors)
                }
                // v0.23 : chacun tape sa plaque ; elle est dessinée ici puis posée sur la voiture 3D.
                SettingRow("Plaque", plateMessage ?: "Affichée sur la voiture ; ne quitte pas l'autoradio") {
                    androidx.compose.foundation.Image(plateImage, null, Modifier.height(30.dp).padding(end = 8.dp))
                    Field(plateText, { plateText = PlateModel.normalize(it) }, "Plaque", Modifier.width(170.dp), placeholder = PlateModel.EXAMPLE)
                    Button(enabled = !plateBusy, modifier = Modifier.padding(start = 8.dp), onClick = {
                        plateBusy = true
                        val wanted = plateText
                        Thread {
                            plateMessage = runCatching { PlateModel.apply(ctx, wanted) }.getOrElse { "Échec : ${it.message}" }
                            plateBusy = false
                        }.start()
                    }) { Text(if (plateBusy) "…" else "Poser") }
                }
                SettingRow("Optimiser l'autoradio", "Mémoire, applis inutiles, mode CarPlay, réglages qui accélèrent Android") {
                    Button(onClick = { optim = true }) { Text("Ouvrir") }
                }
                SettingRow(
                    "Kilométrage",
                    vs.odometerText()?.let { t -> t + (vs.odometerOrigin()?.let { " · $it" } ?: "") } ?: "Non saisi",
                ) {
                    TextButton(onClick = { editingKm = true }) { Text("Modifier") }
                }
                SettingRow(
                    "Température extérieure",
                    if (fr.p308.lanceur.vehicle.OutsideTemp.status.collectAsStateValue().value.found != null)
                        "Lue sur l'autoradio" + (vs.outsideTempC?.let { " : %.0f °C".format(it) } ?: "")
                    else "Pas encore trouvée : recopie celle de l'autoradio une fois",
                ) {
                    TextButton(onClick = { outsideTemp = true }) { Text("Régler") }
                }
                // v0.21 : la page développeur d'Android, que les réglages du fabricant ne montrent pas.
                SettingRow("Options pour les développeurs", devMessage ?: DevOptions.status(ctx)) {
                    TextButton(onClick = { devMessage = DevOptions.openAndroidSettings(ctx) }) { Text("Réglages Android") }
                    Button(onClick = { devMessage = DevOptions.open(ctx) }) { Text("Ouvrir") }
                }
                // v0.22 : ADB par le Wi-Fi, réglé par le Lanceur si l'autoradio l'accepte (jusqu'au prochain redémarrage).
                SettingRow("ADB par le Wi-Fi (pour le PC)", wifiAdbMessage) {
                    Button(enabled = !wifiAdbBusy, onClick = {
                        wifiAdbBusy = true
                        val wanted = !wifiAdbOn
                        Thread {
                            wifiAdbMessage = runCatching { DevOptions.setWifiAdb(wanted) }.getOrElse { "Échec : ${it.message}" }
                            wifiAdbOn = DevOptions.wifiAdbPort() != null
                            wifiAdbBusy = false
                        }.start()
                    }) { Text(if (wifiAdbBusy) "…" else if (wifiAdbOn) "Couper" else "Activer") }
                }
                SettingRow("Thème", if (Night.active) "Nuit en ce moment" else "Jour en ce moment") {
                    Segmented(NightMode.entries.map { it to it.label }, UiPrefs.nightMode) { UiPrefs.updateNightMode(it) }
                }
                SettingRow("Heure", fr.p308.lanceur.vehicle.GpsClock.status()) {
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Android") }
                }
                SettingRow("Style Verre", "Panneaux translucides sur fond dégradé (décoche pour le style classique)") {
                    Switch(UiPrefs.glass, UiPrefs::updateGlass)
                }
                SettingRow("Baisser la luminosité la nuit", "Écran moins éblouissant en thème nuit") {
                    Switch(UiPrefs.dimAtNight, UiPrefs::updateDimAtNight)
                }
                SettingRow("Sauvegarde", backupMessage ?: ("Tout (entretien, trajets, batterie, chauffes, réglages). " +
                    fr.p308.lanceur.backup.AutoBackup.lastText(ctx))) {
                    Row {
                        TextButton(onClick = { backupMessage = Backup.save(ctx) }) { Text("Sauvegarder") }
                        TextButton(onClick = { backupMessage = Backup.restore(ctx) }) { Text("Restaurer") }
                    }
                }
                SettingRow("Mode démo", "Simule le roulage (roues + route), les portes, les phares et les radars") {
                    Switch(Vehicle.demo, Vehicle::updateDemo)
                }
                SettingRow("Utiliser comme écran d'accueil", "Réversible : décoche pour revenir au lanceur d'origine") {
                    Switch(isHome, { HomeAlias.setEnabled(ctx, it); isHome = it })
                }
                SettingRow("Fenêtre flottante sur CarPlay", if (Settings.canDrawOverlays(ctx)) "Autorisée" else "Non autorisée") {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { Toast.makeText(ctx, "Écran absent sur cet autoradio : autorisation à donner via ADB", Toast.LENGTH_LONG).show() }
                    }) { Text("Ouvrir") }
                }
                SettingRow(
                    "Performances à côté de CarPlay",
                    "Panneau : puissance, couple, rapport conseillé, eau, turbo (bouton CarPlay du dock ; fenêtre flottante requise)",
                ) {
                    Switch(UiPrefs.perfOnCarPlay, UiPrefs::updatePerfOnCarPlay)
                }
                if (UiPrefs.perfOnCarPlay) {
                    SettingRow("Côté du panneau", null) {
                        Segmented(listOf(false to "Gauche", true to "Droite"), UiPrefs.perfOnRight) { UiPrefs.updatePerfOnRight(it) }
                    }
                    SettingRow(
                        "Taille du panneau",
                        "Réduit : pastille en haut. Auto : complet pendant la chauffe ou en cas d'alerte, pastille une fois chaud",
                    ) {
                        Segmented(fr.p308.lanceur.PerfSize.entries.map { it to it.label }, UiPrefs.perfSize) { UiPrefs.updatePerfSize(it) }
                    }
                    // Revérifié toutes les 2 s : l'autorisation se donne dans les réglages d'Android, puis on revient.
                    val usage by produceState(fr.p308.lanceur.service.CarPlayWatch.hasAccess(ctx)) {
                        while (true) {
                            delay(2000)
                            value = fr.p308.lanceur.service.CarPlayWatch.hasAccess(ctx)
                        }
                    }
                    SettingRow(
                        "Ouvrir avec CarPlay",
                        if (!UiPrefs.perfAutoCarPlay) "Le panneau ne s'affiche qu'avec le bouton CarPlay du Lanceur"
                        else if (usage) "Le panneau s'affiche aussi quand CarPlay s'ouvre tout seul (téléphone branché)"
                        else "Autorise « Accès aux données d'utilisation » pour le Lanceur 308 (bouton Autoriser)",
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (UiPrefs.perfAutoCarPlay && !usage) {
                                TextButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Autoriser") }
                            }
                            Switch(UiPrefs.perfAutoCarPlay, UiPrefs::updatePerfAutoCarPlay)
                        }
                    }
                }
                SettingRow("Paramètres Android", "Réglages d'origine, souvent cachés par l'autoradio") {
                    TextButton(onClick = { openSettings(ctx, Intent(Settings.ACTION_SETTINGS)) }) { Text("Ouvrir") }
                }
                SettingRow("Options pour les développeurs", "Pour activer le débogage USB / ADB") {
                    // L'écran direct d'abord : l'action générique tombe sur « non disponible » si Android hésite.
                    TextButton(onClick = { openSettings(ctx, *devSettingsIntents()) }) { Text("Ouvrir") }
                }
                SettingRow("Source des données", vs.source) {}
                SettingRow(
                    "Modèle 3D",
                    "« Peugeot 308 5-Door MK1 2007-2021 » par Merc_TV (Sketchfab), licence CC BY 4.0 — modifié : assemblage, peinture noire",
                ) {}
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
    if (editingKm) OdometerDialog(vs.odometerKm) { editingKm = false }
    if (optim) OptimDialog { optim = false }
    if (outsideTemp) OutsideTempDialog { outsideTemp = false }
}

/** Écran des options pour les développeurs (animations, débogage), selon la version d'Android. */
internal fun devSettingsIntents() = arrayOf(
    Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
    Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity"), // Android 8
    Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings"),
    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
)

/** Ouvre le premier écran de réglages que l'autoradio accepte. */
internal fun openSettings(ctx: android.content.Context, vararg intents: Intent) {
    val ok = intents.any { intent ->
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
    if (!ok) Toast.makeText(ctx, "Écran bloqué par l'autoradio", Toast.LENGTH_LONG).show()
}

private class AppEntry(val label: String, val pkg: String, val icon: ImageBitmap)
private var cachedApps: List<AppEntry>? = null

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var assigning by remember { mutableStateOf<AppEntry?>(null) }
    val apps by produceState(cachedApps ?: emptyList()) {
        if (cachedApps != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .sortedBy { it.label.lowercase() }
        }
        cachedApps = loaded
        value = loaded
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.97f)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Applis", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Appui long : assigner au dock", fontSize = 13.sp, color = C.Sub)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f)) {
                items(apps, key = { it.pkg }) { app ->
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { launchPackage(ctx, app.pkg); onDismiss() },
                                onLongClick = { assigning = app },
                            )
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(app.icon, app.label, Modifier.size(56.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(app.label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    assigning?.let { app ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            containerColor = C.Card,
            title = { Text("Assigner « ${app.label} »") },
            text = {
                Column {
                    Slot.entries.forEach { slot ->
                        TextButton(onClick = { UiPrefs.setSlot(slot, app.pkg); assigning = null }) {
                            Text("au bouton ${slot.label}" + if (UiPrefs.slots[slot] == app.pkg) " ✓" else "")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigning = null }) { Text("Annuler") } },
        )
    }
}
