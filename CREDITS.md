# Crédits

Lanceur 308 est écrit par **kylianprebost075-maker**, avec l'aide d'assistants IA pour le code et les guides.
© 2026, tous droits réservés : voir [LICENSE.md](LICENSE.md).

Le projet s'appuie sur le travail d'autres personnes. Chaque élément ci-dessous garde sa propre licence.

## Modèle 3D de la voiture

- **« Peugeot 308 5-Door MK1 2007-2021 »** par **Merc_TV (szymonpasterczyk)**, publié sur Sketchfab :
  https://sketchfab.com/3d-models/peugeot-308-5-door-mk1-2007-2021-ce299e549e594d029577d0b757f3aa87
- Licence **Creative Commons Attribution 4.0 (CC BY 4.0)** : https://creativecommons.org/licenses/by/4.0/
- Modifications faites pour ce projet : sélection des pièces de la phase 1, assemblage des roues et des charnières,
  matériaux (peinture, phares et feux allumables), allègement, plaque d'exemple « AB-123-CD ».
- Fichier : `projet/lanceur-308/app/src/main/assets/models/car.glb` (crédit repris dans l'application, Réglages →
  Modèle 3D, et dans `assets/models/CREDITS.txt`).

## Bibliothèques incluses dans l'application

| Bibliothèque | Auteur | Licence |
|---|---|---|
| SceneView (affichage 3D) | SceneView / Thomas Gorisse et contributeurs | Apache 2.0 |
| Filament (moteur de rendu 3D) | Google | Apache 2.0 |
| Jetpack Compose, AndroidX, Material 3, icônes Material | Google / Android Open Source Project | Apache 2.0 |
| Kotlin et kotlinx.coroutines | JetBrains | Apache 2.0 |

Texte de la licence Apache 2.0 : https://www.apache.org/licenses/LICENSE-2.0

## Outils utilisés pour fabriquer et vérifier (non inclus dans l'application)

| Outil | Usage | Licence |
|---|---|---|
| Gradle, Android Gradle Plugin | fabrication de l'application | Apache 2.0 |
| JUnit 4 | tests automatiques | Eclipse Public License 1.0 |
| glTF-Transform | assemblage du modèle 3D (`outils/assembler_308.mjs`) | MIT |
| three.js | aperçus du modèle 3D (`modele-3d/apercu.html`, captures du README) | MIT |

## Projets qui ont donné des idées

Aucun code de ces projets n'a été copié ; ils ont servi de comparaison ou d'inspiration.

- **Dashphonic** (vsevalid) : façon de parler aux autoradios FYT.
- **Dashwheel** (deviloufr-ai) : organisation d'un tableau de bord pour plusieurs autoradios.
- **AndrOBD** (fr3ts0n) : découpage d'un lecteur OBD.
- **Reality Design — Car Launcher** : idées de l'écran « Prêt à partir » et du carnet d'entretien en PDF.

## Guides

Les captures du guide d'installation montrent les sites et logiciels de Google (Antigravity) et de GitHub, à seule fin
d'explication. Voir la mention sur les marques dans [LICENSE.md](LICENSE.md).
