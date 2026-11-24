# Pistes d'amélioration — Lanceur 308

Relecture du 17 novembre 2026 (version 0.42). Ce fichier dit ce qui a été corrigé pendant la relecture, ce qu'il
reste à vérifier dans la voiture, et ce qu'il faudrait faire ensuite, du plus utile au moins utile.

Ce qui a été relu : toute la logique (dossiers `vehicle/`, `drive/`, `maintenance/`, `backup/`, le service de
surveillance) et les écrans Entretien, Batterie et Chauffe. Pas relus en détail : la scène 3D (`Car3D.kt`,
`LightScene.kt`), l'onglet Son, l'optimisation de l'autoradio et les réglages.

## 1. Corrigé pendant la relecture (v0.42)

| Défaut trouvé | Preuve | Correction |
|---|---|---|
| **Batterie dite « Bonne » à tort.** Un démarrage noté sans la chute au démarreur (relevé du CAN) était jugé sur la seule tension au repos. | 16/11 : matin mesuré à 8,6 V au démarreur (« Faible »), soir noté par le CAN à 12,6 V au repos → l'écran et le bilan « Prêt à partir » affichaient « Bonne ». | Le verdict vient du dernier démarrage **mesuré** (moins de 21 jours). Sans mesure : « Repos correct », en gris, jamais « Bonne ». |
| **Puissance et couple surestimés à charge partielle.** La charge OBD (PID 04) comprend les frottements. | Au ralenti moteur chaud, elle lit déjà 35-36 % (journaux du 9 au 16/11) alors que le moteur ne fournit rien. C'est aussi ce qui donnait 9,8 L/100 estimés pour 5,5 réels. | La charge de ralenti est apprise toute seule (arrêt, 650-950 tr/min, eau ≥ 60 °C) puis retirée : charge utile = (charge − ralenti) / (100 − ralenti). La pleine charge ne change pas. |
| **Conso affichée à côté de CarPlay : une estimation**, alors que la voiture donne la vraie. | Le canal CAN 7/102 (conso instantanée) est lu depuis la 0.39 mais le panneau ne s'en servait pas. | En roulant, le panneau affiche la conso instantanée de l'ordinateur de bord. |
| **Entretien : impossible de simplement lire une case.** Toucher ouvrait le formulaire de modification ; la note et l'intervalle étaient coupés. | Capture de l'onglet : « tous les 1… ». | Toucher une case ouvre une **fiche** : état, dernière fois, km parcourus depuis, intervalle, prochaine échéance, note entière, interventions de l'historique liées. « Modifier » ouvre le formulaire. |
| **Import de factures : mauvais entretien mis à jour.** | Un élément sans mot « match » correspondait à tout : le premier de la liste (la vidange) était modifié. | Sans « match », c'est le nom qui sert de repère. |
| **Journal trop bavard.** | 16/11 : 356 lignes `[CAN7]` pour un trajet (presque toutes « — → 0 ») et 45 lignes `[COMPARE]`. | Valeurs nulles au départ et compteurs de trajet plus écrits ; `[COMPARE]` toutes les 5 à 10 min (le codage de la tension est confirmé). |
| Restauration d'une sauvegarde : dernier plein détecté pas relu ; date d'entretien illisible = plantage au démarrage. | Lecture du code. | `TankGauge.init` ajouté ; date illisible ignorée. |
| Tableaux Excel incomplets. | Lecture du code. | Colonnes ajoutées : origine de la conso (ordinateur de bord ou estimation), température extérieure et origine du relevé des démarrages, eau plafonnée. |

Tests : 100 (8 nouveaux).

## 1 bis. Résultats de l'essai du 17/11 (0.42) et corrections de la 0.43

| Point | Résultat | Suite |
|---|---|---|
| Charge de ralenti | Apprise à 9 h 44 : 40 %, puis suivie lentement jusqu'à 36,5 % le soir. Anciens trajets ramenés à l'échelle (× 0,57). | OK. |
| Alerte thermostat | Sortie à 9 h 35 (« l'eau ne dépasse pas 73 °C, 3 trajets sur 3 »). Plafonds gardés : 73, 72, 77. | OK. Le thermostat est à changer. |
| Chauffe | Le soir, moteur chaud en 8,3 min avec la cible abaissée à 70 °C (avant : jamais). | OK. |
| Batterie | Matin : 8,9 V au démarreur par 13 °C (OBD) → Faible, comme le 16/11 (8,6 V). Soir : le CAN a vu 10,6 V → « Bonne ». | **Faux bon verdict** : le CAN rate le creux. Corrigé en 0.43. Batterie à faire tester. |
| Conso (CAN 102 lu en L/100) | 4,4 L/100 le matin (42 km), 3,8 L/100 sur les 11 premiers km du soir, moteur froid en ville. Ordinateur de bord : 5,5 en moyenne. | **Invraisemblable** : un diesel froid en ville consomme plus. La valeur est peut-être en L/h. La 0.43 note les deux lectures et la conso du trajet selon les compteurs de l'ordinateur de bord. |
| Rafraîchissement de la conso instantanée | 175 à 296 changements en 10 min moteur chaud (toutes les 2 à 3 s). | Assez rapide pour l'indice « gazole en trop » du FAP. |
| Connexion OBD | 7,8 s et 6,9 s (boîtier qui se réveille), 2,3 s quand il est déjà réveillé. | OK. |

**Test à faire pour trancher la conso** : remettre à zéro le **trajet 2** du tableau de bord au départ d'un trajet,
puis lire la ligne `[CONSO]` du journal. Elle donne la conso selon l'ordinateur de bord (« trajet 2 : X L/100 sur N
km ») en face des deux lectures de la valeur 102 : celle qui colle est la bonne.

## 1 ter. FAP lu dans le calculateur (0.44), d'après PSA-RE

[PSA-RE](https://github.com/prototux/PSA-RE) (Apache 2.0) décrit les identifiants de diagnostic des calculateurs PSA.
Pour le moteur EDC16C3 des 308 T7 (`diag/AEE2004.ev/CMM/EDC16C3`) : **21 CA** = température aval catalyseur,
pression différentielle, charge du FAP, distance moyenne entre régénérations, km depuis la dernière ; **21 D1** =
masse d'additif dans le FAP (format différent de celui de cette voiture : 59 octets contre 27, pas décodé).

Le 09/11, 21 CA a été refusé (`7F 21 12`) dans le dialogue ouvert par `81`. PSA-RE donne la session PSA `10 C0`,
envoyée seule le 08/11 (refusée) mais jamais après `81`. La 0.44 essaie `81` → `10 C0` → `21 CA` à chaque
connexion. À lire à la prochaine clé : lignes `[FAP-CMM]`.
- **Si ça marche** : la charge du FAP et les km depuis la dernière régénération remplacent la détection au débit
  d'air et au gazole, qui ne servira plus que de secours.
- **Si c'est refusé** : la ligne donne les trois réponses exactes. Pistes suivantes : `27 83` (accès sécurisé, clé
  connue dans PSA-RE) — **à ne faire qu'avec l'accord de l'auteur**, c'est hors de la lecture simple ; ou le
  calculateur d'additif par le câble des broches 3 et 8.

## 1 quater. Résultats de l'essai du 18/11 (0.44) et correction de la 0.45

| Point | Résultat | Suite |
|---|---|---|
| Conso instantanée (CAN 7/102) | Trajet de 41,4 km : **5,5 L/100 en la lisant en L/h**, 4,2 en L/100, 6,8 estimée par l'OBD. La moyenne de l'ordinateur de bord (7/99) est à 5,4-5,5. Trajet de 1,3 km moteur froid : 8,1 en L/h contre 3,4 en L/100. Le 16/11, la valeur tombait à 3-7 au ralenti, ce qui n'a de sens qu'en L/h. | **Tranché : dixièmes de L/h.** Corrigé en 0.45 partout (trajets, panneau à côté de CarPlay, indice « gazole en trop » du FAP, dont la table apprise repart de zéro). Les trajets enregistrés par la 0.43-0.44 sont relus avec la bonne valeur ; ceux d'avant gardent l'estimation OBD. Les compteurs de trajet de la voiture (2 414 km) n'ont pas été remis à zéro : la preuve vient de la moyenne et du ralenti, pas du test prévu. |
| FAP dans le calculateur (21 CA) | Refusé : `81` → « NO DATA », `10 C0` → `7F 10 12`, `21 CA` → `7F 21 12`. | La session PSA ne s'ouvre pas ainsi. Suite possible : `27 83` (accès sécurisé) — **seulement avec l'accord de l'auteur**. La détection au débit d'air reste la méthode. |
| Batterie | 8,7 V au démarreur par 12 °C → Faible, 4e matin de suite sous 9 V. | Rien à corriger dans l'appli : la batterie est à faire tester. |
| Thermostat | L'eau plafonne à 72 °C, alerte « 4 trajets sur 4 ». | Thermostat à changer. |
| Rafraîchissement 7/102 | 331 à 378 changements en 10 min moteur chaud. | OK pour l'indice du FAP. |
| Journal | 202 octets nuls au milieu du fichier du 18/11 (contact coupé pendant une écriture). | Sans gravité (fichier texte) ; à nettoyer à la lecture si ça gêne un jour. |
| OBD le soir | Trajet de 18 h : connexion commencée, jamais terminée (« Broken pipe » puis Bluetooth inactif). | À surveiller ; le trajet de 19 h 30 s'est connecté en 4,8 s. |

## 1 quinquies. Résultats du 19 au 24/11 (0.47 puis 0.49) et correction de la 0.50

| Point | Résultat | Suite |
|---|---|---|
| Attente turbo au démarrage | Montrée **2 fois sur 17 démarrages** (23/11 et 24/11 au matin : 30 s, terminée à l'arrêt). Les deux fois, le démarreur venait 45 s après le contact. Les 15 autres (démarrage vu par le CAN, démarreur juste après le contact) : rien. | **Cause** : l'autoradio s'endort quelques secondes après la coupure du contact, avant les 20 s qui confirment l'arrêt du moteur ; au réveil le Lanceur le croyait toujours tournant. **Corrigé en 0.50** : plus de 30 s sans relevé = autoradio endormi = moteur coupé ; valeurs du réveil ignorées 4 s (périmées). 3 tests de plus. |
| Banc de puissance (0.49, accélération tirée du régime) | 24/11 : 120, 114, 120, 115, 125, 119 ch ; 302, 272, 278, 265, 293, 279 Nm. Valeur retenue (médiane des 5 dernières) : **119 ch, 278 Nm**. Avant la 0.49 : 91 à 127 ch. | Bien plus régulier (± 5 %). Au-dessus de la fiche (110 ch, 240 Nm) de 8 % et 15 % environ, dans la marge annoncée (± 10-15 %). Pas de correction sans mesure de référence (banc de garage) : masse, rendement et rapports de boîte décalent tout d'un même pourcentage. |
| Conso | Trajet du 24/11 : 5,9 L/100 (CAN en L/h), moyenne affichée 5,3, estimation OBD 4,8. | OK (confirmée le 20/11 par le compteur trajet 1 remis à zéro : 5,8 contre 5,8). |
| Batterie | Démarreur : 8,1 V le 23/11 (10 °C, Faible), 9,8 V le 24/11 (6 °C, Bonne). | Très variable d'un matin à l'autre : toujours à faire tester. |
| Thermostat | Eau plafonnée à 70-72 °C, alerte 4 trajets sur 4. | Thermostat à changer. |
| FAP dans le calculateur | Refusé encore le 24/11 (`81` répond maintenant `C1 D0 8F`, mais `10 C0` et `21 CA` toujours refusés). | Abandon automatique après 3 refus (0.48). |
| Bouton de l'ordinateur de bord (`[COMMODO]`) | Une seule ligne « — → 0 » au réveil, aucun appui noté. | À appuyer une fois en roulant pour le confirmer. |

## 2. À vérifier dans la voiture

Les versions 0.39 à 0.42 n'ont jamais tourné sur l'autoradio. À la prochaine clé USB, regarder dans `journal/` :

1. `[PUISSANCE] charge au ralenti apprise : NN %` : doit sortir après 20 à 30 s de ralenti à l'arrêt, moteur chaud,
   avec une valeur proche de 35 %. Ensuite, puissance et couple doivent tomber à 0 au ralenti.
2. `[CONSO] trajet de N km : ordinateur de bord X, estimation OBD Y` : X doit être proche du tableau de bord (≈ 5,5).
   Avec la charge de ralenti apprise, Y devrait s'approcher de X sans correction.
3. `[FAP] … conso instantanée changée N fois en 10 min` : si N est petit (moins de 20), le canal 102 est trop lent
   pour repérer la post-injection et le signal « gazole en trop » ne servira pas.
4. Chauffe : « Moteur aussi chaud qu'il peut » quand l'eau plafonne, et les chauffes de nouveau enregistrées.
5. Alerte thermostat après 3 plafonds sur 4 chauffes.
6. Fiche d'entretien : lisible sur l'écran 1280 × 720, et les interventions liées sont les bonnes.

## 3. À faire ensuite, par ordre d'utilité

### Sur la voiture elle-même (l'appli a déjà trouvé les pannes)
- **Thermostat** : l'eau plafonne à 71-77 °C à chaque trajet. Pièce peu chère ; un moteur trop froid consomme plus
  et encrasse la vanne EGR et le FAP.
- **Batterie** : 8,6 à 9,2 V au démarreur par 10 à 15 °C. À faire tester avant l'hiver.

### Méthode (le vrai point faible du projet)
- **Une version = un essai dans la voiture.** Neuf versions en cinq jours, dont six jamais essayées : la 0.33
  annonçait 9,8 L/100 pendant trois jours sans que personne le voie. Ne publier une release qu'après un trajet.
- **Écrire dans le CHANGELOG le résultat de l'essai** (comme pour la 0.38), pas seulement ce que la version fait.

### Mesures
- **FAP** : caler le signal « gazole en trop » sur une vraie régénération (noter l'heure quand le ralenti monte ou
  que le ventilateur tourne après l'arrêt). Tant que ce n'est pas fait, il ne déclenche rien seul.
- **Niveau de gazole** : les canaux CAN 105 et 108 sont les compteurs des trajets 1 et 2, 106 et 109 leur conso
  moyenne. Avec le câble adaptateur (broches 3 et 8, voir `outils/obd/SEANCE_OBD_2.md`), le BSI donnerait la vraie
  jauge et l'additif du FAP.
- **Chute au démarreur par le CAN** : l'autoradio n'envoie pas la tension assez vite. Seule solution sans OBD :
  rien. Avec l'OBD : il est connecté en 5 s quand le boîtier est réveillé, 14 s sinon. Laisser 15 s entre le
  contact et le démarrage donne une mesure à chaque fois.
- **Score de conduite et huile estimée** : ils utilisaient la puissance surestimée. À recaler après quelques
  trajets en 0.42 (l'huile montera un peu moins vite).

### Code
- **`Engine.kt` (plus de 850 lignes)** mélange le Bluetooth (SPP et BLE), le dialogue OBD, le diagnostic et le
  kilométrage. À couper en trois fichiers : transports, boucle de lecture, diagnostic.
- **`VehicleWatchService.kt` (plus de 500 lignes)** : toutes les alertes au même endroit, avec des noms de classes
  écrits en entier partout. Sortir les alertes dans un fichier à part.
- **État global** : presque tout est dans des `object` avec un état modifiable. Les tests se partagent cet état (il
  faut remettre à zéro à la main). À terme : des classes créées par test.
- **Tests manquants** sur les parties qui gardent un état dans le temps : `Warmup.tick`, `TripLog.tick`,
  `FapRegen.tick` avec les deux signaux, `TankGauge.onCanRange`.
- **Deux corrections du froid pour la batterie** : `CrankWatch.crankLimit` (température) et
  `BatteryHealthModel.corrected` (paliers sur l'eau). À unifier.
- **`ObdLog`** coupe les lignes à 300 caractères et deux écritures peuvent se croiser. Sans gravité, à surveiller si
  les lignes s'allongent.

### Présentation et diffusion
- **README de plus de 950 lignes** : c'est un journal de développement. Garder la vitrine et « En bref », déplacer
  le reste dans `docs/` (attention : `CLAUDE.md`, `AGENTS.md` et `GEMINI.md` y renvoient).
- **Captures** : celles des onglets datent de la 0.16 (émulateur, vue de dessus). Les refaire sur l'autoradio.
- **Licence** : « tous droits réservés » freine les étoiles et les contributions. Décision de l'auteur.
- **Releases** : joindre l'APK à chaque release (la 0.38 n'en a pas).
- **Anglais** : l'appli est en français seulement ; une traduction ouvrirait le projet aux forums étrangers.
