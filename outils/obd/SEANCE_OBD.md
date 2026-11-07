# Séance OBD dans la voiture — pas à pas

But : découvrir tout ce que les calculateurs de la 308 acceptent de donner (codes défaut, vanne EGR, FAP,
kilométrage, état des feux…), pour programmer ensuite l'onglet **Diagnostic** du Lanceur 308 (v0.9).

**Lecture seule, garantie par le programme** : `obd_explorer.py` refuse tout ce qui modifie la voiture
(effacement des codes, écriture, tests d'actionneurs, régénération du FAP, accès sécurisé, reprogrammation,
remise à zéro). Ces commandes ne partent jamais vers le boîtier ; c'est vérifié par `test_obd_explorer.py`.

Durée : 30 à 45 minutes. Il faut : le PC chargé, le **Vgate vLinker MC+**, le téléphone en partage de connexion.

---

## Avant de partir (à la maison)

1. **Charger le PC à 100 %** et le garder ouvert (couvercle fermé = mise en veille = séance coupée).
2. Choisir un moment **en journée** : pendant la séance, le PC n'est plus sur le réseau de la maison. Les tâches
   qui tournent dessus (le bot de crypto, par exemple) peuvent manquer des relevés.
3. Préparer le **partage de connexion** du téléphone : Claude a besoin d'Internet pour travailler.

## Dans la voiture

4. Garer la voiture **dehors** (jamais moteur tournant dans un garage fermé).
5. Brancher le vLinker sur la prise OBD (sous le volant, à gauche), **mettre le contact** sans démarrer.
   Le voyant du vLinker s'allume.
6. **Première fois seulement** : sur le PC, Paramètres → Bluetooth et appareils → Ajouter un appareil →
   Bluetooth → « **vLinker MC-Android** » → code **1234**.
7. Dire à Claude « c'est branché ». Claude lance, dans l'ordre :

   | Étape | Commande | Ce que tu fais | Durée |
   |---|---|---|---|
   | Test | `connexion` | rien | 10 s |
   | OBD normalisé | `standard` | rien | 1 min |
   | Calculateurs PSA | `psa` | rien (ne touche à rien dans la voiture) | 5 à 15 min |
   | Feux | `instantane --nom ...` puis `comparer` | allumer / éteindre les feux **quand Claude le demande** : éteints, veilleuses, croisement, route, antibrouillards avant et arrière | 5 min |
   | Kilométrage | `chercher-km <km>` | lire le kilométrage du compteur à Claude | 1 min |

   ⚠️ Contact mis moteur arrêté : **20 minutes au plus** en tout, sinon la batterie se vide. Si ça dure,
   démarrer le moteur (dehors) et continuer moteur tournant.
8. **Démarrer le moteur** (dehors). Claude relance `standard` (valeurs moteur tournant), puis
   `direct --minutes 5` : laisse tourner au ralenti, ou roule normalement avec le PC posé sur le siège
   passager — **ne touche pas au PC en conduisant**, le relevé continue seul.
9. Fin : couper le contact. Le vLinker peut rester branché quelques jours (il se met en veille), mais le
   débrancher si la voiture reste longtemps sans rouler.

## Ce qui en sortira

- Les valeurs que la 308 donne vraiment, avec l'endroit où les lire (calculateur, identifiant, octet).
- Les codes défaut présents, avec leur explication.
- De quoi écrire l'onglet **Diagnostic** du Lanceur : codes défaut (lecture, puis effacement avec confirmation),
  vanne EGR, FAP, kilométrage, feux si le BSI les donne.

Les journaux vont dans `outils/obd/journaux/` : ils restent sur le PC et **ne sont jamais publiés** (ils
contiennent le numéro de série de la voiture). Le VIN est masqué dans les résumés.

---

## Notes pour l'IA qui mène la séance

- Lancer : `python -I -X utf8 outils/obd/obd_explorer.py <commande>` (bibliothèque standard seulement, aucun
  module à installer). Essai sans voiture : ajouter `--simulation`.
- Si le bac à sable de l'outil de commandes bloque le Bluetooth (comme il bloque les connexions locales de
  Gradle), lancer les commandes dans le terminal du panneau, qui tourne hors bac à sable.
- Les paires d'adresses PSA de `CALCULATEURS_PSA` sont des candidates **à confirmer** : `psa` dit lesquelles
  répondent. Si aucune ne répond, essayer d'autres paires avant de conclure.
- Feux : un instantané par état (`phares_eteints`, `veilleuses`, `croisement`, `route`, `antibrouillard_av`,
  `antibrouillard_ar`), puis `comparer` deux à deux. Un octet qui change à chaque fois = l'état des feux.
- Ne jamais élargir la liste blanche pour « essayer » une commande d'écriture : une telle fonction se décide
  avec l'utilisateur, à part, et se teste à l'arrêt.
