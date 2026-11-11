# Réglage du son dans la voiture — pas à pas

Matériel : la 308 avec le Lanceur v0.15, le PC portable chargé (micro intégré), un mètre ruban.
Durée : 45 min environ. Moteur coupé, contact mis, portes et vitres fermées. Garde le volume raisonnable.

Sur le PC : double-clic sur `outils\son\mesure_son.bat` (la première fois il prépare l'outil : Internet nécessaire,
1 à 2 minutes, à faire à la maison). Sur l'autoradio : dock → **Son**.

## 1. Alignement temporel (sans PC)
1. Assis en position de conduite, mesure du milieu de ta tête au centre de chaque haut-parleur : les 2 tweeters Focal
   sous le pare-brise, les 2 Pioneer à l'arrière. Note-les dans Son → Alignement.
2. Recopie les retards (ms, ou cm si l'écran de l'autoradio compte en cm) dans Réglages de l'autoradio → Son / DSP.
   Le haut-parleur le plus loin reste à 0.
3. Niveaux : baisse les plus proches du nombre de dB indiqué si l'autoradio le permet par voie.

## 2. Tests (sans PC)
- Son → Tests : chaque côté en son pur et en basses, fader tout à l'avant puis tout à l'arrière. Un haut-parleur muet
  ou qui grésille : vérifier son branchement.
- **Polarité** : « Lancer le test A / B ». A doit être plus plein que B. Si B a plus de basses, un haut-parleur est
  branché à l'envers (inverser + et − d'un seul). À faire à l'avant puis à l'arrière.
- Balayage : écoute les vibrations de garnitures (portières, plage arrière) et note la fréquence où ça vibre.

## 3. Mesures au micro du PC
Pose le PC ouvert sur le siège conducteur, micro (en haut de l'écran en général) à hauteur de tes oreilles.
- **Clics (choix 2)** : vérifie l'alignement réglé à l'étape 1, fader à l'avant puis à l'arrière. Le PC dit de combien
  de millisecondes corriger. Refaire jusqu'à moins de 0,1 ms.
- **Courbe (choix 1)** : bruit rose au volume d'écoute habituel. Le PC donne les réglages d'égaliseur (10 bandes) et
  un graphique (dossier `mesures`). Règle, puis refais la mesure pour vérifier. Ne monte pas les basses de plus de
  +3 dB d'après le PC : son micro capte mal le grave, fie-toi à ton oreille en dessous de 80 Hz.
- **Saturation (choix 3)** : tape au PC le cran de départ affiché sur l'autoradio, puis lance la montée. Sors de la
  voiture pendant la montée (le son devient fort). Le PC donne le volume maximum sans saturer l'ampli : au-delà, les
  tweeters souffrent. Si le PC dit « micro saturé », recule-le et recommence.

Les enregistrements restent dans `outils\son\mesures` (jamais publiés).

## 4. Options
- Volume selon la vitesse (Léger / Moyen / Fort) et annonces vocales : Son → Options.
- Piloter le DSP de l'autoradio directement : Son → Options → « Chercher les applis son » → « Copier sur la clé USB »,
  puis rapporter la clé au PC pour étudier les commandes. Rien n'est modifié sur l'autoradio.

## Réglage automatique (v0.30, depuis le PC, autoradio relié par ADB Wi-Fi)

`python reglage_auto.py` fait tout seul, en 3 minutes environ : bruit de fond, courbe au bruit rose, montée lente des
basses (le micro entend le grésillement des portes et en déduit la fréquence), réglage des 15 bandes de l'égaliseur FYT
(points glissés par ADB ; valeurs d'avant notées dans le rapport), fréquence de vibration envoyée au Lanceur
(`reglage-son.json`, basses automatiques activées), puis nouvelle courbe pour comparer. Rapport et graphique avant / après
dans `mesures/` (jamais publié). Options : `--sans-eq`, `--sans-vibration`, `--micro N`, `--appareil IP:5555`.
