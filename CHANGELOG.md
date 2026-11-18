# Journal des versions

Les versions publiées se téléchargent sur la page
[Releases](https://github.com/kylianprebost075-maker/lanceur-308/releases). Une mise à jour s'installe par-dessus
l'ancienne version et garde les réglages. Le détail technique de chaque version est dans le [README](README.md).

## 0.46 — 18 novembre 2026 (pas encore essayée sur l'autoradio)
- **Banc de puissance** (Moteur → Banc) : la vraie puissance et le vrai couple, mesurés pendant une accélération à
  fond. Le Lanceur repère tout seul l'accélération (pied au plancher, un seul rapport), relève vitesse et régime, puis
  trace la courbe puissance / couple selon le régime, avec la dernière mesure et le record. Calcul : la force aux roues
  sert à accélérer la voiture et à vaincre l'air et le roulement (masse, aérodynamique et rendement de la 308 dans
  `CarProfile.kt`). C'est une estimation à ± 10-15 % : à faire sur route plate, sans vent, en 3e ou 4e depuis
  1 500 tr/min jusqu'à la zone rouge. Rien n'est envoyé à la voiture.

## 0.45 — 18 novembre 2026 (pas encore essayée sur l'autoradio)
- **Conso juste** : la conso instantanée envoyée par la voiture est en litres par heure, pas en L/100 (essai du
  18/11 : 5,5 L/100 sur 41 km en la lisant ainsi, comme la moyenne de l'ordinateur de bord). Corrigé pour la conso
  des trajets, le panneau à côté de CarPlay (en L/h à l'arrêt) et l'indice « gazole en trop » du FAP, qui réapprend
  de zéro. Les trajets des versions 0.43-0.44 sont relus avec la bonne valeur.
- Le journal `[CONSO]` note aussi la moyenne affichée par la voiture, pour comparer d'un coup d'œil.

## 0.44 — 17 novembre 2026 (essayée le 18/11 : lecture du FAP refusée par le calculateur, conso tranchée en L/h)
- **FAP lu dans le calculateur moteur** : charge du filtre (%), pression différentielle, km depuis la dernière
  régénération et distance moyenne entre deux, comme les applis de diagnostic. Décodage tiré de
  [PSA-RE](https://github.com/prototux/PSA-RE) (licence Apache 2.0). Lu à chaque connexion du boîtier OBD, puis toutes
  les 15 min moteur tournant ; affiché dans Moteur → Bilan. Quand les km depuis la dernière régénération repassent
  à zéro, la régénération est notée (mesure sûre, en plus de la détection au débit d'air).
- **À confirmer dans la voiture** : le 09/11 le calculateur avait refusé cette lecture ; elle est maintenant
  demandée dans la session de diagnostic PSA (« 10 C0 »), jamais essayée jusqu'ici. En cas de refus, les réponses
  exactes sont écrites au journal (`[FAP-CMM]`) et l'appli ne réessaie que le lendemain.

## 0.43 — 17 novembre 2026 (pas encore essayée sur l'autoradio)
- **Batterie : une bonne chute vue par l'autoradio ne compte plus.** L'autoradio envoie la tension trop lentement
  pour voir le vrai creux au démarreur : le 17/11, il a vu 10,6 V le soir (« Bonne ») quand l'OBD mesurait 8,9 V le
  matin. Une chute vue par l'autoradio n'est prise en compte que si elle est déjà mauvaise.
- **Conso : vérification contre l'ordinateur de bord.** La conso instantanée lue sur l'autoradio donnait des chiffres
  trop bas (4,4 L/100 le matin, 3,8 en ville moteur froid). Le journal note maintenant, à chaque fin de trajet, la
  conso calculée en lisant cette valeur en L/100 et en L/h, l'estimation OBD, et la conso du trajet selon les
  compteurs 1 et 2 de l'ordinateur de bord. **Pour une mesure exacte : remettre à zéro le trajet 2 du tableau de bord
  au départ.**

## 0.42 — 17 novembre 2026 (essayée le 17/11 : fiche d'entretien, charge de ralenti apprise à 40 % puis 36 %, alerte thermostat sortie, chauffe terminée à la cible abaissée)
Relecture complète du code ; le détail et la suite sont dans [PISTES.md](PISTES.md).
- **Entretien : fiche de chaque case.** Toucher un entretien ouvre une fiche à lire (état, dernière fois, km
  parcourus depuis, intervalle, prochaine échéance, note entière, interventions de l'historique liées). Le bouton
  « Modifier » ouvre le formulaire, qui s'ouvrait avant directement.
- **Batterie : plus de « Bonne » sans mesure.** Un démarrage noté sans la chute au démarreur affichait « Bonne »
  d'après la seule tension au repos, et masquait un démarrage mesuré « Faible » le matin même. Le verdict vient
  maintenant du dernier démarrage mesuré ; sans mesure, l'écran dit « Repos correct ».
- **Puissance et couple plus justes.** La charge lue par l'OBD comprend les frottements (35 % au ralenti sur ce
  moteur) : elle est apprise au ralenti puis retirée. Au ralenti, la puissance affichée tombe à 0 ; à pleine charge
  rien ne change. L'estimation de conso par l'OBD devient plus juste elle aussi.
- **Conso à côté de CarPlay** : en roulant, c'est la conso instantanée de l'ordinateur de bord qui est affichée.
- Import de factures : un élément sans mot repère ne modifie plus la vidange à la place du bon entretien.
- Journal allégé (lignes `[CAN7]` et `[COMPARE]`), tableaux Excel complétés (origine de la conso, température et
  origine du relevé des démarrages, eau plafonnée), restauration d'une sauvegarde plus sûre.

## 0.41 — 16 novembre 2026 (pas encore essayée sur l'autoradio)
- **FAP : la détection n'est plus aveugle avec un thermostat ouvert.** Elle ignorait toutes les mesures sous 75 °C
  d'eau ; le seuil suit maintenant la cible de chauffe (4 °C dessous, 60 °C au moins).
- **FAP : deuxième indice, le gazole en trop (post-injection).** La post-injection ne se lit pas sur ce calculateur,
  mais c'est du gazole brûlé sans effort moteur : la conso instantanée de l'ordinateur de bord × la vitesse est
  comparée à la normale apprise par régime et charge. Ce signal confirme le débit d'air (détection plus tôt quand les
  deux sont d'accord) ; seul, il est noté au journal sans compter comme régénération, le temps de le vérifier sur une
  vraie régénération.

## 0.40 — 16 novembre 2026 (pas encore essayée sur l'autoradio)
- **Chauffe réparée avec un thermostat ouvert** : l'eau plafonnant vers 75-77 °C, la chauffe n'atteignait jamais
  80 °C, restait « en cours » tout le trajet et n'était jamais enregistrée. Maintenant, quand l'eau plafonne, la
  chauffe s'arrête là (« Moteur aussi chaud qu'il peut ») ; et si le thermostat est jugé ouvert, la cible passe à 3 °C
  sous le plafond habituel (affiché), puis revient à 80 °C une fois le thermostat réparé.
- Le thermostat est surveillé à chaque trajet, même sans chauffe en cours.

## 0.39 — 16 novembre 2026 (pas encore essayée sur l'autoradio)
- **Conso lue sur l'ordinateur de bord** (autoradios FYT, boîtier CAN PSA) : la conso moyenne (canal 7/99) est lue
  toute seule, plus besoin de la taper ; la conso de chaque trajet vient de la conso instantanée de la voiture
  (7/102) × km parcourus, au lieu de l'estimation OBD. Journal : `[CONSO]` en fin de trajet, les deux côte à côte.
- Démarrage noté par le CAN sans la chute au démarreur : le journal précise que le verdict vient du repos seul.

## 0.38 — 15 novembre 2026 (essayée sur l'autoradio le 16/11 : tension CAN juste à 0,1 V près, démarrage repéré par le CAN, OBD connecté en 5 s)
- **Démarrages mesurés par le CAN** : depuis le 13/11, le boîtier OBD arrivait moteur déjà tourné et plus aucun
  démarrage n'était noté. La tension du boîtier CAN de l'autoradio, là dès le contact, sert maintenant à les repérer
  (repos, chute au démarreur si le boîtier l'envoie assez vite, charge). Journal : `[DEMARRAGE] (CAN)`.
- **Verdict de la batterie corrigé du froid** : la tension mini acceptable baisse avec la température (9,6 V à 21 °C,
  8,5 V à -18 °C). Un matin froid ne fait plus passer une batterie saine pour « faible ».
- **Alerte thermostat** : si l'eau plafonne sous 80 °C en roulant à 3 des 4 dernières chauffes, une alerte dit que
  le thermostat reste sans doute ouvert (au plus une fois par semaine).
- **OBD plus rapide** : nouvel essai toutes les 1,5 s tant que le Bluetooth s'allume, tout de suite au réveil de
  l'autoradio ou quand le CAN voit le moteur démarrer, 2 s après une coupure ; kilométrage moteur relu au plus toutes
  les 3 h à la connexion. Le journal note le temps de connexion.
- **Moins de gêne pour CarPlay** : plus de recherche BLE quand le boîtier SPP habituel ne répond pas, essais espacés
  à 15 s quand il reste muet (voiture garée), nettoyage de la mémoire 20 s après l'ouverture de CarPlay au lieu de
  pendant la connexion du téléphone.

## 0.37 — 15 novembre 2026 (pas encore essayée sur l'autoradio)
- **Conso juste** : l'estimation OBD donnait environ 9,8 L/100 alors que l'ordinateur de bord affiche 5,5. Dans
  Entretien → Pleins, un bouton « Conso de l'ordi de bord » permet de taper sa valeur : elle corrige la conso des trajets
  et sert à calculer les litres restants. Deux pleins complets notés au carnet restent prioritaires.
- **Plein détecté seulement à l'arrêt** : en roulant, l'autonomie de l'ordinateur peut remonter de 100 km d'un coup
  (920 → 1020 km le 13/11 sur autoroute). Ce n'est plus pris pour un plein. Les litres ajoutés sont calculés avec la
  bonne conso (≈ 46 L au lieu de 60 pour le plein du 12/11).
- **Recherche de la conso sur le CAN** : les canaux inconnus du boîtier sont notés au journal (`[CAN7]`), pour trouver
  celui de la conso de l'ordinateur de bord et la lire toute seule dans une prochaine version.

## 0.36 — 13 novembre 2026 (pas encore essayée sur l'autoradio)
- **Voiture 3D** : si tu as tourné la vue au doigt, elle revient toute seule à sa place quand une porte s'ouvre (ou
  à la marche arrière, ou au départ), pour montrer le bon côté. Avant, seule la voiture pivotait un peu.
- **Plaque** : « Poser » recharge la voiture même si le texte n'a pas changé ; si la plaque ne peut pas s'afficher,
  les Réglages disent pourquoi. Quand un modèle 3D envoyé par le PC cache ta plaque, une ligne « Plaque cachée »
  propose de le retirer.

## 0.35 — 12 novembre 2026 (version d'essai, pas encore essayée sur l'autoradio)
- **Essai du micro** (rien de visible dans l'appli) : pour savoir si le Lanceur pourrait baisser la musique quand
  quelqu'un parle, sans la baisser quand on chante, le PC peut enregistrer le micro de l'autoradio quelques minutes
  (`outils/adb/essai_micro.ps1`). L'appli déclare pour cela l'autorisation du micro, mais ne la demande jamais à
  l'écran : seul le PC branché en ADB la donne, lance l'enregistrement, récupère le son, puis la retire. Le son ne
  quitte pas le PC. Si l'essai ne donne rien, l'autorisation sera retirée de l'appli.

## 0.34 — 12 novembre 2026 (pas encore essayée sur l'autoradio)
- **Plein écran** : la barre d'Android du haut est cachée dans le Lanceur (elle reste dans les autres applis). Un
  glissement depuis le bord du haut la montre quelques secondes.
- **Retiré : « Volume selon la vitesse »** (le son qui montait puis redescendait autour de 50, 80 et 110 km/h). Le
  Lanceur ne touche plus au volume en roulant.

## 0.33 — 12 novembre 2026 (essayée sur l'autoradio du 12 au 15/11 : plein détecté, conso trop haute, corrigée en 0.37)
- **Litres restants lus tout seuls** (autoradios FYT qui transmettent l'autonomie de l'ordinateur de bord) : le
  Lanceur déduit les litres de cette autonomie et les affiche sur l'accueil (« Autonomie · ≈ 31 L ») et dans
  Entretien → Pleins. C'est une estimation : la voiture ne donne son niveau de gazole ni par la prise OBD ni par le
  boîtier CAN, seulement son autonomie.
- **Plein détecté tout seul** : quand l'autonomie remonte d'un coup d'au moins 100 km, le Lanceur annonce le plein
  et les litres ajoutés (bandeau, voix, notification), et propose de le noter au carnet (il ne manque que le prix).
- **Sans cette information** (autre voiture) : Entretien → Pleins → « Niveau du réservoir » (Plein, 3/4, 1/2, 1/4,
  Réserve ou litres) ; le Lanceur recalcule ensuite les litres et l'autonomie en roulant, d'après ta consommation.

## 0.32 — 11 novembre 2026 (pas encore essayée sur l'autoradio)
- **Passagers automatique** (Son → Alignement) : quand une porte passager (avant droite, arrière) s'ouvre autoradio
  allumé et voiture à l'arrêt, le son passe tout seul en « Passagers » ; au trajet suivant, il revient à « Seul ». Un
  choix fait à la main n'est jamais changé ; interrupteur pour couper l'option. Les ceintures, essayées le 11/11, ne
  sont pas transmises par le boîtier CAN : les portes sont le seul signe d'un passager.
- **Redémarrer l'autoradio** depuis Applis (bouton en haut, avec confirmation). Marche sur les autoradios dont
  SELinux est « Permissive » (cas de l'autoradio de l'auteur) ; sinon l'appli dit de passer par le bouton marche.

## 0.31 — 11 novembre 2026 (pas encore essayée sur l'autoradio)
- **Batterie sans boîtier OBD** : la tension est lue par le boîtier CAN de l'autoradio (FYT). Moteur coupé et autoradio
  allumé, un rappel toutes les 20 min (« la batterie se vide »), avec le temps écoulé dans Moteur → Batterie.
- **Son « Seul / Passagers »** (Son → Alignement) : un appui règle la balance de l'égaliseur FYT, centrée sur le
  conducteur ou au centre pour tout le monde. Le Lanceur touche l'égaliseur à ta place grâce à un service
  d'accessibilité, à activer une fois (Android → Accessibilité) ; il n'agit que dans l'égaliseur.
- **Véhicule → Mon autoradio** : ce que l'autoradio transmet vraiment (température, batterie, volume, portes, marche
  arrière, autonomie, boîtier CAN), plus OBD, GPS et égaliseur. Pratique pour savoir ce qui marche sur une autre voiture.
- **Réglage du son pour deux places** (`reglage_auto.py --deux-places`) : mesure aussi à la place passager et règle
  l'égaliseur pour le meilleur compromis (60 % conducteur, 40 % passager).
- Outils : test des ceintures par le CAN (`outils/adb/test_ceintures.ps1`). Résultat du 11/11 : le boîtier de l'auteur ne transmet pas les ceintures ; tension et balance vérifiées sur l'autoradio.

## 0.30 — 11 novembre 2026
- **Basses automatiques** (Son → Options) : plus de basses quand le volume est bas, et à volume fort la fréquence qui
  fait vibrer les portes est baissée. Une montée lente des basses (30 → 150 Hz) et un bouton « Ça vibre » trouvent cette
  fréquence. Agit sur la musique et CarPlay, pas sur la radio FM.
- **Réglage du son automatique depuis le PC** (`outils/son/reglage_auto.py`) : le PC pilote l'autoradio par le Wi-Fi,
  mesure au micro, règle l'égaliseur 15 bandes et envoie la fréquence de vibration des portes au Lanceur.

## 0.29 — 11 novembre 2026
- **Onglet Son → Alignement refait** pour les autoradios FYT : leur égaliseur ne règle pas de retard par haut-parleur.
  À partir des distances aux deux tweeters, l'appli dit où placer le point dans la croix « Champ » de l'égaliseur (combien
  de crans à droite et vers l'avant), avec un bouton pour ouvrir l'égaliseur et un bruit rose pour finir à l'oreille.

## 0.28 — 11 novembre 2026
- **Température extérieure lue directement** sur les autoradios FYT : elle s'affiche comme dans la barre du haut, sans
  rien régler. L'ancien réglage « Température extérieure » reste en secours pour les autres autoradios.
- **Voiture 3D mieux cadrée** sur les écrans 1280 × 720 : elle n'est plus coupée en bas, les plaques sont visibles.

## 0.27 — 11 novembre 2026
- Droits d'auteur : [LICENSE.md](LICENSE.md) (tous droits réservés, usage personnel gratuit) et crédits complets :
  [CREDITS.md](CREDITS.md). La mention apparaît aussi en bas des Réglages de l'appli.

## 0.26 — 11 novembre 2026
- **Bilan « Prêt à partir »** au démarrage : risque de verglas, batterie, carburant, entretien. Il se ferme tout seul,
  ne s'affiche que s'il a quelque chose à dire, et se coupe dans les Réglages.
- **Carnet d'entretien en PDF** : Entretien → Historique → « Carnet en PDF ». Il est aussi écrit sur la clé USB à
  chaque sauvegarde (dossier `export`).
- Ajout de ce journal et de [CONTRIBUTING.md](CONTRIBUTING.md).

## 0.25 — 10 novembre 2026
- Guide illustré en PDF pour installer avec Antigravity (`docs/guide-antigravity/`).
- Corrigé : le texte de la plaque se dédoublait pendant la frappe avec certains claviers.
- Corrigé : adapter l'appli à une autre voiture faisait échouer des tests propres à la Peugeot 308.

## 0.24 — 10 novembre 2026 (première version publique)
- Tout ce qui dépend de la voiture est regroupé dans un seul fichier, `CarProfile.kt`.
- [ADAPTER.md](ADAPTER.md) : feuille de route pour l'IA qui aide quelqu'un à installer ou adapter l'appli.
- Le modèle 3D à plaque d'exemple est publié : le projet se compile chez tout le monde.

## 0.23 — 10 novembre 2026
- Plaque d'immatriculation tapée dans les Réglages et posée sur la voiture 3D (elle ne quitte pas l'autoradio).
- Écran de bienvenue au premier lancement ; guide [INSTALLER.md](INSTALLER.md).

## 0.19 à 0.22 — 10 novembre 2026
- Fiche technique de l'autoradio et journal des plantages écrits sur la clé USB.
- Réglages : options pour les développeurs, ADB par le Wi-Fi.
- Premiers tests automatiques des calculs.

## 0.9 à 0.18 — 7 au 9 novembre 2026
- Panneau performances à côté de CarPlay, onglet Moteur (diagnostic, codes défaut, batterie, trajets, chauffe, bilan,
  filtre à particules), consommation et autonomie estimées.
- Sauvegarde automatique sur la clé USB, tableaux pour Excel, import des factures.
- Onglet Son, optimisation de l'autoradio, style « Verre », kilométrage lu dans le calculateur.

## 0.5 à 0.8 — 5 et 6 novembre 2026
- Voiture en 3D avec ouvrants, phares et vue conducteur ; rendu fluide sur un autoradio modeste.
- Données moteur par boîtier OBD Bluetooth (Vgate vLinker MC+, iCar Pro, ELM327).
