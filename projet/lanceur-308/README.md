# Lanceur 308

Écran d'accueil Android sur mesure pour l'autoradio de la Peugeot 308 T7 1.6 HDi 110 (2007).

- **Accueil** : la 308 noire en 3D sur plateau studio (360° avec flèches, ou vue intérieur depuis le siège conducteur), vue de dessus en incrustation pour ouvrants et radar, heure, température, batterie, autonomie, kilométrage, puissance et régime.
- **Moteur** : puissance estimée en direct (cadran + pic), régime, charge, vitesse, turbo, température d'eau — via un adaptateur OBD2 ELM327 Bluetooth.
- **Véhicule** : état des 6 ouvrants et distances des 4 capteurs de recul.
- **Entretien** : suivi au km et/ou au mois, valeurs de départ pour le DV6, alertes à 1 000 km / 30 jours puis en cas de dépassement.
- **Fenêtre flottante** par-dessus CarPlay quand un ouvrant s'ouvre.
- **Réglages** : couleur, 3/5 portes, kilométrage, mode démo, devenir l'écran d'accueil (réversible).

## Compiler

Voir le `README.md` à la racine du dépôt (§3) : `.\gradlew.bat assembleRelease` sous Windows,
`./gradlew assembleRelease` sous macOS, avec Java 17.

APK : `app/build/outputs/apk/release/app-release.apk` (version optimisée R8, ~16 Mo), signée avec la clé de debug :
toujours la même, sinon l'installation par-dessus l'ancienne version échoue.

**Le modèle 3D `app/src/main/assets/models/car.glb` n'est pas publié** : le reconstruire avec les outils du dossier
`outils/` (README racine, §5). Sans lui, l'appli compile mais l'accueil n'affiche pas la voiture.

Performances (émulateur Android 8) : démarrage 0,4–0,7 s, 87 Mo de mémoire, 3D à 0 image/s au repos et
28 images/s au plus, 60 000 triangles dessinés ; 0 % de processeur hors de l'accueil ou quand CarPlay est au premier plan.

## Crédits

Modèle 3D : « Peugeot 308 5-Door MK1 2007-2021 » par Merc_TV (szymonpasterczyk) sur Sketchfab, licence CC BY 4.0.
Modifié avec `outils/assembler_308.mjs` et `outils/finaliser_car_glb.py` (pièces phase 1, roues, matériaux, allègement).
