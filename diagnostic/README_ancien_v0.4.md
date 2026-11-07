# Lanceur 308 — dossier de reprise

À lire en entier avant de toucher à quoi que ce soit. Ce dossier permet à une IA (ou à un développeur) de reprendre le projet exactement où il en est.

## 1. Contexte

- **Voiture** : Peugeot 308 T7 (phase 1, 2007), 1.6 HDi 110 ch (moteur DV6TED4), 5 portes, **noire**.
- **Autoradio** : Android chinois 2-DIN de plateforme **FYT** (applis système `com.syu.*`, `com.fyt.*`), **Android 8.0 (API 26)** même si l'écran affiche « Android 16 ». CarPlay sans fil via l'appli FYT `com.syu.carlink` / ZLink.
- **Accès à l'autoradio** : **pas d'ADB**. Le débogage sans fil n'existe pas sous Android 8, et l'ADB réseau (port 5555) est fermé même avec « Débogage USB » activé. On installe donc par **fichier APK** : l'utilisateur l'envoie sur l'autoradio par SwissTransfer ou par clé USB, puis l'installe depuis le gestionnaire de fichiers.
- **Règles de sécurité de départ** :
  - ne jamais désinstaller d'appli système (seulement `pm disable-user`) ;
  - ne pas toucher à la radio, au CAN, au DSP, à la caméra, au lanceur d'origine, à CarPlay, au Bluetooth ni au GPS ;
  - toujours montrer la liste avant de modifier et attendre l'« ok » de l'utilisateur.

  Le script `outils/can_probe.sh` (et les scripts `mod.sh` / `rollback.sh` d'origine, dans `~/autoradio-308/` sur le Mac) ont été préparés pour un accès ADB qui n'a jamais été obtenu.

## 2. L'appli « Lanceur 308 » (`projet/lanceur-308/`)

C'est une appli Android en Kotlin et Jetpack Compose.

**Identité et cibles**
- Paquet `fr.p308.lanceur`, version 0.4 (`versionCode = 4`).
- minSdk 26, targetSdk 33, compileSdk 35.

**Compilation**
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01.
- 3D : **SceneView 2.2.1** (Filament 1.52). Les versions 2.3+ demandent Kotlin 2.2 : ne pas monter de version sans mettre Kotlin à jour.
- Version finale avec R8 (`proguard-rules.pro` garde `com.google.android.filament.**` et `io.github.sceneview.**`).
- Signature avec la clé de débug du Mac (`~/.android/debug.keystore`) : **garder la même clé**, sinon l'installation par-dessus échoue.

**Commande de compilation** (Java 17 Homebrew, SDK `/opt/homebrew/share/android-commandlinetools`) :
```bash
cd projet/lanceur-308
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk (~16,8 Mo)
```

### Fonctionnalités (toutes testées sur émulateur Android 8 et 13)

| Onglet | Contenu | Fichiers |
|---|---|---|
| Accueil | 308 en 3D sur plateau « studio » (rotation, flèches ‹ ›, vue **Intérieur** conducteur), ouvrants animés (portes, coffre, capot), **phares avant + faisceaux + feux arrière rouges + feux de recul** et antibrouillards allumés, heure, température, batterie, autonomie, km, puissance, régime | `ui/Screens.kt`, `ui/Car3D.kt`, `ui/LightScene.kt` (rendu limité à 30 images/s) |
| Véhicule | État des ouvrants et radar (vue de dessus) · **GPS** (diagnostic des gels CarPlay) · **Diagnostic CAN** | `ui/GpsPanel.kt`, `ui/CanProbePanel.kt`, `vehicle/GpsMonitor.kt`, `vehicle/CanProbe.kt`, `vehicle/FytBridge.kt` |
| Moteur | Puissance estimée (OBD ELM327 Bluetooth), chrono 0-100 et 80-120, trajet | `vehicle/Engine.kt`, `drive/Drive.kt`, `ui/EngineScreen.kt`, `ui/DrivePanels.kt` |
| Entretien | Suivi de l'entretien du DV6 avec alertes · carnet de **Pleins** | `maintenance/Maintenance.kt`, `maintenance/Fuel.kt`, `ui/FuelPanel.kt` |
| Réglages | Couleur, 5 portes, km, thème Auto/Jour/Nuit, baisse de luminosité la nuit, sauvegarde/restauration USB, mode démo, écran d'accueil (alias HOME désactivé par défaut), fenêtre flottante, accès aux options développeur cachées | `ui/Dialogs.kt`, `backup/Backup.kt`, `ui/Night.kt` |

**En arrière-plan**, le service `service/VehicleWatchService.kt` gère :
- le GPS (km, gels), les alertes d'entretien et de batterie ;
- le bandeau par-dessus CarPlay quand une porte s'ouvre ;
- l'OBD et le CAN FYT.

**Mode démo** (Réglages) : il simule les ouvrants, les antibrouillards, la marche arrière, une batterie faible et des accélérations.

## 3. Le modèle 3D (`modele-3d/`)

- **Source** : « Peugeot 308 5-Door MK1 2007-2021 » par Merc_TV (szymonpasterczyk) sur Sketchfab, licence **CC BY 4.0**. La mention est affichée dans les Réglages. C'est un export du jeu BeamNG qui contient toutes les variantes (phase 1, restylage, GTi) avec des pièces décalées.
- **Script d'assemblage** : `outils/assembler_308.mjs` (gltf-transform 4, installé dans `~/autoradio-308/outils/node_modules`). Il :
  - garde les pièces de la phase 1 et remet les pare-chocs en place ;
  - monte 4 roues ;
  - ajoute les **charnières** (`charniere_porte_AVG/AVD/ARG/ARD`, `charniere_coffre`, `charniere_capot`) ;
  - remplace les matériaux : noir verni, vitres teintées semi-transparentes, habitacle, matériau `antibrouillard` à émission pilotée par l'appli ;
  - applique la plaque `plaque.png` (générée par `plaque.html`, non publiée) ;
  - allège le tout (~60 000 triangles dessinés, 1,7 Mo).
- **Commande** :
  ```bash
  node assembler_308.mjs ../modele-3d/source/308_5p.glb ../modele-3d/car.glb
  ```
  Copier ensuite le résultat dans `projet/lanceur-308/app/src/main/assets/models/car.glb`.
- **Aperçu** : `apercu.html` (three.js) avec les vues `?v=detail|roues|interieur|plaques&ouvert=1`.

## 4. LE POINT BLOQUANT : les portes ne s'animent pas dans la voiture

Ce qui a été établi en décompilant les APK de l'autoradio (`fyt-extraits/`, avec jadx) :
- Les données CAN passent par le service **`com.syu.ms`** : `bindService(Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"))`. On obtient ensuite `IRemoteToolkit.getRemoteModule(7)` (module **7 = canbus**), puis `IRemoteModule.register(callback, code, 1)`.
- Les numéros de transaction, vérifiés, sont : toolkit 1 = getRemoteModule ; module 1 = cmd, 2 = get, 3 = register, 4 = unregister ; callback 1 = update(int, int[], float[], String[]).
- **Codes des ouvrants** (profils PSA 308 : `Callback_0187_RZC4_PSA308`, `Callback_0029_WC1_BiaoZhi308`, `Callback_0339_RZC4_PSA_ALL`) :
  - 0 = capot, 1 = porte avant gauche, 2 = avant droite, 3 = arrière gauche, 4 = arrière droite, 5 = coffre ;
  - `ints[0] != 0` signifie ouvert.
- **C'est déjà codé** : `vehicle/FytBridge.kt` gère les transactions Binder, et `vehicle/CanBusSource.kt` est démarré par le service.
- **Mais** dans le rapport `diagnostic/diagnostic-can.txt` (porte conducteur puis coffre ouverts pendant l'écoute), la connexion à `com.syu.ms` réussit (modules 0 à 15 abonnés, 616 canaux) et **aucun canal du module 7 ne change**. Seuls bougent les canaux 101, 146 et 77 du module 0, dont le sens n'a pas été identifié.
- **Hypothèse principale** : l'autoradio est mal réglé sur le boîtier CAN ou le modèle de voiture, et ne reçoit donc pas les portes. À vérifier :
  - la fenêtre « porte ouverte » **d'origine** de l'autoradio (DoorHelper) s'affiche-t-elle quand on ouvre une porte ?
  - si non, demander une photo du menu **Paramètres → Réglages voiture / Canbus** et choisir le profil 308 adapté au boîtier : Raise/RZC4 → `0187_RZC4_PSA308` ; XP/Hiworld → `0281_XP_PSA_ALL` ; WC/Simple → `0029_WC1_BiaoZhi308` ou `0252_WC2_PSAALL`.
- Les APK complètes de l'autoradio sont sur le Mac, dans `~/Downloads/Android/data/fr.p308.lanceur/files/apk-can/`, et le décompilé complet dans `~/autoradio-308/decompile/`.

## 5. Autres points en attente

1. **Gel GPS sous CarPlay** (le problème de départ) :
   - l'utilisateur doit faire un trajet en passager avec Véhicule → GPS, appuyer sur « Waze / Plans vient de se figer » à chaque gel, puis copier le journal sur la clé ;
   - au retour, lancer `outils/analyser_gps.sh`, qui conclut si le GPS de l'autoradio se fige aussi ;
   - si oui : couper l'envoi du GPS du véhicule dans l'appli CarPlay FYT ou ZLink. Sinon : tester CarPlay en filaire.
   - **Jamais testé avec un vrai GPS** : l'émulateur Android 8 n'injecte pas de position.
2. **OBD** : le code ELM327 Bluetooth classique est écrit, mais l'adaptateur n'a jamais été branché. Il faut aussi vérifier que le Bluetooth de l'autoradio est accessible aux applis ; sinon, ajouter un ELM327 USB.
3. **Antibrouillards et phares sur le CAN** : les codes ne sont pas encore identifiés (thème nuit en Auto : coucher du soleil, car `headlights` vaut null). À chercher dans le profil PSA décompilé (codes ≥ 6).
4. **Son / DSP** (demandé au départ, jamais fait) :
   - alignement temporel à partir des distances tête-haut-parleur (343 m/s, le haut-parleur le plus éloigné à 0 ms) ;
   - Focal à l'avant avec tweeters, Pioneer à l'arrière, égaliseur 48 bandes, ampli TDA7851.
5. **Accessibilité** (idée de la vidéo du créateur original) : un AccessibilityService qui lit la fenêtre « porte ouverte » de l'appli CAN, en solution de secours.

## 6. Outils et livrables

- `livrables/Lanceur308.apk` : dernière version (avec la lecture CAN FYT). `livrables/PROCEDURE.txt` : procédure pour l'utilisateur.
- `outils/tester_sur_mac.sh` : émulateur Android 8 (AVD `autoradio-android8`, 1024×600) avec fenêtre, sur le Mac.
- `outils/mesure.sh` : mesures de performance (démarrage, CPU, mémoire).
- `outils/analyser_gps.sh` + `analyse_gps.py` : analyse des journaux GPS.
- `outils/gps_log.sh` : capture logcat par ADB, utile si l'ADB fonctionne un jour.

## 7. Prompt conseillé pour l'IA qui reprend

> Lis README.md en entier. Reprends le projet « Lanceur 308 » (Android/Kotlin/Compose, autoradio FYT Android 8 sans ADB, installation par APK). Priorité : faire fonctionner les portes. Demande-moi d'abord une photo du menu Réglages voiture / Canbus de l'autoradio, et si la fenêtre de portes d'origine apparaît quand j'ouvre une porte. Explique chaque étape en français, simplement, et livre une APK prête à installer.
