# Séance OBD n° 2 — les calculateurs qui manquent

Préparée le 09/11/2026 après lecture de la notice du vLinker MC+ et de la table publique des calculateurs PSA
(projet arduino-psa-diag). **Lecture seule**, comme la première : rien n'est effacé, écrit ni piloté.

## Ce qu'on a compris depuis le 08/11

1. **Pourquoi le BSI, le compteur, la clim… ne répondaient pas.** Sur les Peugeot de cette génération, les
   valises parlent aux calculateurs par les **broches 3 et 8** de la prise. Le vLinker (comme tous les boîtiers
   du commerce) parle sur les **broches 6 et 14**, où il n'y a que le réseau du moteur : moteur et ABS.
   Sa notice annonce bien un 2e réseau (« MS-CAN »), mais c'est celui des Ford, sur les broches 3 et 11 :
   il ne convient pas à une Peugeot.
   → Il faut un **petit câble adaptateur** (voir plus bas). Sans lui, la séance se limite au moteur et à l'ABS.
2. **Quatre adresses étaient fausses** dans le programme (airbag, direction, stationnement, autoradio).
   La table est maintenant complète : 90 adresses, dont le **boîtier d'additif du FAP** (6C1).
3. **Le calculateur moteur attend la commande `81`** pour ouvrir le dialogue, pas `10C0` (qu'il a refusée le
   08/11, et que la v0.17 du Lanceur envoie pour le kilométrage). C'est la piste pour : lire le kilométrage
   moteur tournant, lire les codes défaut propres à Peugeot, et peut-être voir de nouvelles valeurs (FAP, EGR).

## Notice du vLinker MC+ : ce qui compte

- Voyants : POWER (alimenté), BT (Bluetooth), OBD (parle à la voiture), HOST (parle au PC).
- PC et Android : appairer « **vLinker MC-Android** », code **1234**. Ne pas appairer « vLinker MC+ » ni
  « MC-IOS » (réservés à l'iPhone). En cas d'erreur : « oublier » l'appareil et recommencer.
- Consommation : 24 mA en marche, 3 mA en veille automatique. Il peut rester branché quelques jours.
- Contact mis obligatoire ; moteur arrêté ou tournant selon la mesure.
- Mise à jour du boîtier : appli « VgateFirmwareUpdater » sur téléphone. **Pas nécessaire** : il marche.
- Messages longs : jusqu'à 1024 octets par demande, mémoire de 2 Ko : suffisant pour tout ce qu'on lit.

## Partie A — avec le vLinker seul (moteur et ABS), 25 min

Avant : PC chargé, partage de connexion, voiture dehors, vLinker branché, **contact mis sans démarrer**.

| # | Commande | But | Durée |
|---|---|---|---|
| 1 | `connexion` | le boîtier répond | 10 s |
| 2 | `chercher` | interroge les 90 adresses (au cas où un autre calculateur répond sur ce réseau) | 4 min |
| 3 | `defauts --calculateur moteur` puis `abs` | codes défaut propres à Peugeot (jamais lus jusqu'ici) | 30 s |
| 4 | `balayer --calculateur moteur --service 21 --nom moteur21_s81_arret` | tout relire avec la session `81` : de nouveaux identifiants ? | 3 min |
| 5 | `balayer --calculateur abs --service 21 --nom abs21_s81` | même chose pour l'ABS | 3 min |
| 6 | `comparer moteur21_tournant moteur21_s81_arret` | ce que la session `81` apporte | — |
| 7 | **Démarrer le moteur.** `balayer --calculateur moteur --service 21 --relire moteur21_s81_arret --nom moteur21_s81_tournant` | le kilométrage (2110) se lit-il moteur tournant avec `81` ? quelles valeurs bougent ? | 2 min |
| 8 | Accélérer à 2000 tr/min à l'arrêt, même commande avec `--nom moteur21_s81_2000` | repérer une valeur qui suit le régime (EGR, air, turbo) | 2 min |
| 9 | `comparer` deux à deux | — | — |

À vérifier aussi : `2111` doit avoir augmenté depuis le 08/11 (11 834 902) si c'est bien le temps moteur.

## Partie B — avec le câble adaptateur (BSI, compteur, additif FAP, clim, airbag…), 30 min

Même boîtier, branché **au bout du câble**. Moteur et ABS ne répondent plus (normal), les autres oui.

| # | Commande | But |
|---|---|---|
| 1 | `chercher` | la liste des calculateurs de la voiture |
| 2 | `psa` | lit tous les identifiants de chacun (10 à 20 min ; brancher un chargeur ou démarrer) |
| 3 | `defauts --calculateur …` pour chacun | codes défaut |
| 4 | `instantane --nom …` par état des feux, des portes, puis `comparer` | feux, portes, **température extérieure** |
| 5 | `chercher-km <km du compteur>` | kilométrage du BSI et du compteur |
| 6 | `balayer --calculateur 6C1 --service 21 --nom additif` | niveau d'additif du FAP |

## Le câble adaptateur

- Ce que c'est : une rallonge OBD mâle → femelle de 30 cm dont deux fils sont croisés :
  **broche 3 de la voiture → broche 6 du boîtier**, **broche 8 de la voiture → broche 14 du boîtier**,
  masse (4, 5) et alimentation (16) droites, rien d'autre.
- Deux façons : acheter une rallonge OBD à fils nus ou démontable (5 à 10 €) et la recâbler, ou une prise
  OBD mâle + une femelle à souder. Ça ne modifie pas la voiture.
- Vérifier avant de brancher, au multimètre, contact mis : entre la broche 3 et la masse puis la broche 8 et la
  masse, environ 2,5 V chacune (signe d'un réseau CAN). Si l'une est à 12 V ou à 0 V : ne pas brancher, me le dire.
- Prudence : c'est déduit de la documentation publique, **pas encore vérifié sur cette 308**.

## Notes pour l'IA qui mène la séance

- Lancer dans le terminal du panneau (le bac à sable bloque le Bluetooth) :
  `python -I -X utf8 outils/obd/obd_explorer.py <commande>`.
- `81` et `82` (début et fin de communication, sans paramètre) sont dans la liste blanche depuis le 09/11 :
  ce sont des ouvertures de dialogue, comme `10C0`. Rien d'autre n'a été ajouté. 14, 27, 2E, 2F, 30, 31, 3B
  restent refusés ; ne pas les ajouter pour « essayer ».
- Si `81` est acceptée (réponse `C1`), envoyer `3E` toutes les 2 s est déjà fait par `balayer`.
- Si la partie A.7 lit 2110 moteur tournant : dans `Engine.kt` (`readEcuKm`), remplacer `10C0` par `81` et la
  fermeture `1081` par `82`, puis nouvelle version. Sinon, ne rien changer.
- Résultats à écrire dans `journaux/RESULTATS_<date>.md` (local, jamais publié).
