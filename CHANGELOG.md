# Journal des versions

Les versions publiées se téléchargent sur la page
[Releases](https://github.com/kylianprebost075-maker/lanceur-308/releases). Une mise à jour s'installe par-dessus
l'ancienne version et garde les réglages. Le détail technique de chaque version est dans le [README](README.md).

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
