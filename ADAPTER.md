# ADAPTER.md — instructions pour l'IA qui aide quelqu'un à installer Lanceur 308

> **Tu es une IA (Claude, Gemini / Antigravity, ChatGPT, Copilot…) et quelqu'un vient de t'ouvrir ce projet ?**
> Ce fichier est ta feuille de route. Suis-la dans l'ordre. Ne lis PAS tout le dépôt : le `README.md` fait plus de
> 600 lignes et sert à l'auteur. Tout ce qu'il te faut est ici et dans `CarProfile.kt`.

## Règles

1. **Économise les requêtes.** Beaucoup de personnes utilisent une offre gratuite (Antigravity gratuit, quota de
   requêtes agent limité chaque semaine). Vise **6 actions au plus** du début à la fin : un seul message de
   questions, un seul fichier modifié, une seule construction. N'explore pas le dépôt, ne relis pas un fichier deux fois.
2. **Parle en français simple** (ou dans la langue de la personne). Elle n'est probablement pas développeuse.
3. **Ne demande jamais** la plaque d'immatriculation, le numéro de série (VIN), l'adresse ni le nom de la personne.
   La plaque se tape dans l'appli elle-même (Réglages → Plaque) et ne quitte pas l'autoradio.
4. **Ne touche à rien d'autre** que `projet/lanceur-308/app/src/main/java/fr/p308/lanceur/vehicle/CarProfile.kt`,
   sauf si la personne le demande expressément. Ne fais ni commit, ni push, ni publication.
5. **Sois honnête** : si une fonction ne marchera pas sur sa voiture, dis-le avant qu'elle installe.
6. **Sécurité** : l'appli lit la voiture, elle ne la modifie pas. N'ajoute jamais d'envoi de commandes au calculateur
   (écriture, effacement, actionneurs, codage). Ne propose jamais de changer le « modèle de voiture » ou le boîtier
   CAN dans le menu d'usine de l'autoradio s'il marche déjà.

## Étape 1 — un seul message de questions

Envoie ce questionnaire **en un seul message** (la personne répond à tout d'un coup) :

```
Pour régler Lanceur 308 sur ta voiture, réponds à ces questions (« je ne sais pas » est une réponse valable) :

LA VOITURE
1. Marque, modèle et année (ex. : Renault Clio 3 de 2008)
2. Moteur : cylindrée et puissance (ex. : 1.5 dCi 85 ch), si tu les connais
3. Carburant : essence, diesel, hybride ou électrique ?
4. Boîte : manuelle (combien de rapports ?) ou automatique ?
5. 3 portes ou 5 portes ?
6. Taille des pneus, écrite sur le flanc (ex. : 205/55 R16)
7. Contenance du réservoir en litres, si tu la connais

L'AUTORADIO
8. Marque ou modèle de l'autoradio, et version d'Android (Paramètres → À propos)
9. Est-ce un autoradio « FYT » ? (souvent écrit dans Paramètres → À propos, ou dans le nom du processeur
   UIS / SC7731 ; sinon réponds « je ne sais pas »)
10. Les portes ouvertes ou le radar de recul s'affichent-ils déjà sur l'écran d'origine de l'autoradio ?
    (si oui, un boîtier « CAN-bus » est branché et réglé)
11. Quelle appli pour CarPlay / Android Auto ? (ZLink, Autokit, Carbit… ou aucune)

LE BOÎTIER OBD (optionnel, pour les données du moteur)
12. As-tu un boîtier OBD ? Marque et modèle (ex. : Vgate vLinker MC+, iCar Pro, ELM327 bleu à 10 €),
    et est-il Bluetooth ou Wi-Fi ?

CE QUE TU VEUX
13. A) juste installer l'appli telle quelle, ou B) que je l'adapte à ta voiture (il faudra un ordinateur
    avec Android Studio, ou au moins Java 17 et le SDK Android, pour fabriquer l'appli) ?
```

## Étape 2 — la fiche personnalisée (toujours)

À partir des réponses, écris à la personne une fiche courte : **ce qui marchera, ce qui ne marchera pas, quoi régler**.
Règles (vérifiées dans le code de la version 0.24) :

| Si… | Alors… |
|---|---|
| Android plus ancien que 8.0 | L'appli ne s'installe pas (`minSdk = 26`). Arrête-toi là et dis-le. |
| N'importe quel Android 8.0 ou plus | Marchent : écran d'accueil, voiture 3D, plaque, mode démo, vitesse GPS, chrono, trajets, carnet d'entretien, pleins, rappels. |
| Autoradio FYT **et** boîtier CAN réglé (question 10 = oui) | Portes, coffre, capot, phares et radar de recul s'affichent en direct. Les codes CAN ont été relevés sur une 308 : sur une autre voiture, **à essayer** (onglet Véhicule → Diagnostic CAN montre ce qui arrive). |
| Autoradio non FYT | Pas de portes ni de phares en direct (le reste marche). |
| Pas de boîtier OBD | Pas de régime, température d'eau, turbo, tension, codes défaut. Conseille un **Vgate vLinker MC+** (Bluetooth Low Energy, connexion automatique, sans appairage). |
| Boîtier OBD **Bluetooth classique** (ELM327 et clones) | Il faut l'associer d'abord dans les réglages Bluetooth de l'autoradio (code `1234` ou `0000`), puis onglet Moteur → Rechercher OBD. Les clones à bas prix répondent parfois mal. |
| Boîtier OBD **Wi-Fi** | **Non pris en charge** : seul le Bluetooth (classique ou Low Energy) l'est. |
| Voiture sans prise OBD (en Europe, en gros : essence avant 2001, diesel avant 2004) | Pas de données moteur, même avec un boîtier. |
| Électrique | Presque aucune donnée moteur standard : seules les parties sans OBD marchent. |
| Essence, ou diesel sans filtre à particules | Le suivi du FAP est sans objet → `HAS_DPF = false` si on adapte. |
| Moteur sans turbo | Le conseil « turbo chaud » est sans objet → `HAS_TURBO = false`. |
| Pas une Peugeot/Citroën à moteur diesel HDi (Bosch EDC16) | Le kilométrage n'est pas lu dans le calculateur : le saisir une fois (Réglages → Kilométrage), il avance ensuite au GPS → `PSA_ECU_ODOMETER = false`. |
| Pas adaptée (choix A) sur une autre voiture que la 308 1.6 HDi 110 | Puissance, couple, rapport conseillé et autonomie seront **faux** (calculés pour la 308). Le reste est juste. |
| Boîte automatique | Le rapport affiché est seulement estimé ; les conseils de passage sont à ignorer. |

Réglages à indiquer, quel que soit le choix : Réglages → **Plaque** (taper la sienne → Poser), **couleur**,
**5 portes** (décocher pour 3 portes), **Utiliser comme écran d'accueil**, **Fenêtre flottante sur CarPlay**
(si la personne utilise CarPlay). La voiture affichée reste une Peugeot 308 (voir « Le modèle 3D » plus bas).

Si la personne a choisi **A**, termine en lui donnant le lien d'installation : `INSTALLER.md` (téléchargement de l'APK,
copie sur une clé USB, installation depuis le gestionnaire de fichiers de l'autoradio). Fin.

## Étape 3 — adapter (seulement si la personne a choisi B)

1. Ouvre **un seul fichier** : `projet/lanceur-308/app/src/main/java/fr/p308/lanceur/vehicle/CarProfile.kt`.
   Chaque valeur y est expliquée. Modifie-les toutes en une fois :
   - `NAME` et `ENGINE_LABEL` : texte affiché (24 caractères au plus).
   - `TORQUE_CURVE` : couple maxi (N·m) selon le régime. Tu connais en général la fiche du moteur (couple maxi et à
     quel régime, puissance maxi et à quel régime) : construis 8 à 10 points plausibles qui passent par ces deux
     valeurs (couple = puissance en kW × 9 549 / régime). Dis à la personne que c'est une estimation.
   - `KMH_PER_1000` : km/h à 1 000 tr/min pour chaque rapport. Si tu ne connais pas l'étagement exact, calcule-le
     (rapport de boîte × rapport de pont, circonférence du pneu) ou donne des valeurs approchées : l'appli apprend
     ensuite le vrai étagement en roulant. Autant de valeurs que de rapports.
   - `TANK_L`, `RESERVE_L`, `HAS_DPF`, `HAS_TURBO`, `PSA_ECU_ODOMETER` : d'après les réponses et le tableau ci-dessus.
2. Vérifie avec les tests (ils refusent les valeurs impossibles), depuis `projet/lanceur-308` :
   `gradlew testDebugUnitTest` (Windows : `.\gradlew.bat testDebugUnitTest`).
3. Construis l'appli : `gradlew assembleRelease`. L'APK sort dans `app/build/outputs/apk/release/app-release.apk`.
   Prérequis : Java 17 (`JAVA_HOME`) et le SDK Android (`ANDROID_HOME` ou `local.properties` avec `sdk.dir=…`).
   La signature utilise la clé de test d'Android, créée automatiquement sur l'ordinateur : rien à fournir.
   ⚠️ Une APK construite ainsi ne s'installe pas par-dessus celle téléchargée sur GitHub (signature différente) :
   il faut d'abord désinstaller l'autre (les réglages de l'appli sont alors perdus).
4. Dis à la personne d'installer `app-release.apk` comme expliqué dans `INSTALLER.md`, et de commencer par le
   **Mode démo** pour vérifier que tout s'affiche.

## Le modèle 3D

- L'appli embarque `projet/lanceur-308/app/src/main/assets/models/car.glb` : une Peugeot 308 phase 1 avec une plaque
  d'exemple « AB-123-CD ». Source : « Peugeot 308 5-Door MK1 2007-2021 » par Merc_TV (szymonpasterczyk) sur Sketchfab,
  licence **CC BY 4.0** (crédit obligatoire, déjà affiché dans l'appli et dans `assets/models/CREDITS.txt`).
- **Servir d'exemple** : ce fichier montre exactement ce que l'appli attend d'un modèle. Pour mettre une autre voiture
  (modèle glTF binaire `.glb` sous licence qui autorise la réutilisation), il doit avoir :
  - des nœuds **charnières** qui tournent pour ouvrir : `charniere_porte_AVG`, `charniere_porte_AVD`,
    `charniere_porte_ARG`, `charniere_porte_ARD`, `charniere_coffre`, `charniere_capot` ;
  - des nœuds **roues** qui tournent en roulant : `roue_AVG`, `roue_AVD`, `roue_ARG`, `roue_ARD` (et `moyeu_…`) ;
  - des **matériaux** allumés par l'appli : `phare_avant`, `phare_reflecteur`, `vitre_phare_avant`, `feu_position_ar`,
    `feu_blanc` (recul), `antibrouillard`, `voyant_feux` ;
  - pour la plaque personnalisée : une seule image PNG de **1040 × 220** (la plaque, retournée verticalement), avec
    de la place en réserve (`outils/plaque_glb.py` l'ajoute) ;
  - moins de 100 000 triangles environ : le processeur graphique des autoradios est faible.
  Un nœud absent n'empêche pas l'affichage : la pièce correspondante ne bouge simplement pas.
- **Sans rien reconstruire** : un `car.glb` copié sur l'autoradio dans `Android/data/fr.p308.lanceur/files/car.glb`
  remplace le modèle embarqué (Android 8 à 10 : accessible avec le gestionnaire de fichiers).
- La fabrication du modèle de la 308 (scripts `outils/assembler_308.mjs`, `outils/finaliser_car_glb.py`,
  `outils/plaque_glb.py`) est décrite au §5 du `README.md` : ne la lis que si la personne veut changer de modèle 3D.

## Si quelque chose ne marche pas

- L'appli écrit un journal sur la clé USB laissée branchée : `Android/data/fr.p308.lanceur/files/journal/`. Le message
  de l'onglet Moteur dit toujours où bloque la connexion OBD.
- Ce dossier contient les trajets (positions GPS) et peut contenir le numéro de série de la voiture : ne le fais **jamais** publier en entier ;
  ne recopie que les lignes utiles, sans le VIN.
- Problème dans l'appli elle-même : la personne peut ouvrir un ticket sur
  https://github.com/kylianprebost075-maker/lanceur-308/issues (voiture, autoradio, version, ce qui se passe).
