# Installer Lanceur 308 sur ton autoradio

Lanceur 308 est un écran d'accueil pour autoradio Android : ta voiture en 3D, les portes et les phares en direct,
les données du moteur, l'entretien. Il est gratuit, sans compte et sans publicité.

> **À savoir avant de commencer.** L'appli a été faite et essayée sur **une seule voiture** : une Peugeot 308 de
> 2007 (1.6 HDi 110) avec un autoradio chinois de marque FYT. Sur une autre voiture ou un autre autoradio, une
> partie marche, une partie non (tableau plus bas). Tu l'installes à tes risques : elle ne modifie rien dans la
> voiture, mais personne ne l'a testée sur la tienne.

> **Guide illustré en PDF (Antigravity, démonstration complète sur une Renault Clio)** :
> [Guide-installation-Lanceur308-Antigravity.pdf](https://github.com/kylianprebost075-maker/lanceur-308/raw/main/docs/guide-antigravity/Guide-installation-Lanceur308-Antigravity.pdf)

## Le plus simple : demande à ton IA

Tu utilises Claude, Gemini (Antigravity, même gratuit), ChatGPT ou une autre IA ? Donne-lui ce projet et copie-lui ce
message :

```
Aide-moi à installer Lanceur 308 dans ma voiture : https://github.com/kylianprebost075-maker/lanceur-308
Lis le fichier ADAPTER.md de ce dépôt et suis-le : pose-moi d'abord tes questions sur ma voiture.
```

Dans Antigravity, choisis d'abord le modèle **Gemini 4 Argon** avec la réflexion **High** (menu du modèle, au survol) :
c'est le plus récent du forfait gratuit, et le plus fiable pour suivre le guide.

Elle te pose une seule série de questions (voiture, carburant, boîte, autoradio, boîtier OBD), te dit ce qui marchera
chez toi et quoi régler. Si tu veux, elle adapte aussi l'appli à ta voiture (puissance, rapports, réservoir) : il faut
alors un ordinateur pour fabriquer l'appli. Elle ne te demandera jamais ta plaque ni ton numéro de série.

Tu préfères tout faire toi-même ? Suis les étapes ci-dessous.

## Ce qu'il te faut

- Un autoradio **Android 8 ou plus récent** (écran large, type 1024 × 600).
- Une clé USB.
- En option, pour les données du moteur : un boîtier OBD Bluetooth (ELM327, Vgate vLinker MC+, iCar Pro), 15 à 40 €.

## Installation en 5 étapes

1. Sur ton ordinateur ou ton téléphone, ouvre la page
   [Versions](https://github.com/kylianprebost075-maker/lanceur-308/releases/latest) et télécharge `Lanceur308.apk` (lien direct, toujours la dernière version :
   https://github.com/kylianprebost075-maker/lanceur-308/releases/latest/download/Lanceur308.apk ).
2. Copie ce fichier sur la clé USB.
3. Branche la clé sur l'autoradio et ouvre son **gestionnaire de fichiers**.
4. Touche `Lanceur308.apk`. Si Android demande d'autoriser les « sources inconnues », accepte pour le gestionnaire
   de fichiers, puis touche **Installer**.
5. Ouvre **Lanceur 308** dans la liste des applis.

Pour une mise à jour, refais la même chose : la nouvelle version s'installe par-dessus et garde tes données.

## Premier lancement

Un écran de bienvenue s'affiche une fois. Ensuite :

- **Voir tout de suite ce que ça donne** : Réglages → **Mode démo**. La voiture roule, les portes s'ouvrent, les
  phares s'allument, sans rien brancher. Pense à le couper après.
- **Ta plaque sur la voiture** : Réglages → **Plaque** → tape-la → **Poser**. Elle reste sur ton
  autoradio, elle n'est envoyée nulle part.
- **La couleur de la voiture** : en haut des Réglages.
- **Garder cet écran au démarrage** : Réglages → **Utiliser comme écran d'accueil**. Décoche pour revenir à
  l'écran d'origine de l'autoradio.
- Android demande la position (vitesse GPS, heure) et le Bluetooth (boîtier OBD) : accepte, sinon ces parties
  restent vides.

## Brancher un boîtier OBD (données du moteur)

1. Branche le boîtier sur la prise OBD de la voiture (sous le volant) et mets le contact.
2. Ouvre l'onglet **Moteur** : avec un Vgate vLinker, la connexion se fait toute seule.
3. Sinon : réglages Bluetooth de l'autoradio → associe le boîtier (code `1234` ou `0000`) → onglet Moteur →
   **Rechercher OBD**.

Le message affiché dans l'onglet Moteur dit toujours où ça bloque.

## Ce qui marche selon ta voiture

| Fonction | N'importe quel autoradio Android 8+ | Autoradio FYT | Peugeot 308 (2007-2013) 1.6 HDi + FYT |
|---|---|---|---|
| Écran d'accueil, voiture 3D, plaque, couleur | oui | oui | oui |
| Mode démo | oui | oui | oui |
| Vitesse GPS, chrono, trajets | oui | oui | oui |
| Carnet d'entretien, pleins, rappels | oui | oui | oui |
| Régime, eau, turbo, tension, codes défaut (boîtier OBD) | oui, sur la plupart des voitures après 2004 | oui | oui |
| Portes, coffre, phares, radar de recul en direct | non | à essayer : dépend de la voiture | oui |
| Puissance, couple, rapport conseillé, filtre à particules, kilométrage lu dans le calculateur | réglés pour le 1.6 HDi 110 : faux ou vides ailleurs | idem | oui |

La voiture affichée est toujours une Peugeot 308, même si la tienne est différente.

## Questions fréquentes

**Est-ce que ça peut abîmer ma voiture ?** L'appli ne fait que lire. La seule action qui écrit quelque chose est
« Effacer les codes défaut », et seulement si tu appuies dessus et que tu confirmes.

**Est-ce que mes données partent quelque part ?** Non. L'appli n'a pas l'autorisation d'aller sur Internet. Les
sauvegardes vont uniquement sur ta clé USB si tu en laisses une branchée.

**Comment l'enlever ?** Décoche d'abord « Utiliser comme écran d'accueil », puis désinstalle « Lanceur 308 » dans
les applis d'Android. L'autoradio redevient exactement comme avant.

**L'appli plante ou une fonction ne marche pas ?** Laisse une clé USB branchée, relance l'appli, puis ouvre un
ticket sur la page [Issues](https://github.com/kylianprebost075-maker/lanceur-308/issues) en décrivant ta voiture
et ton autoradio. Attention : le dossier `Android/data/fr.p308.lanceur` de la clé contient tes trajets, ne le
publie pas en entier.

**Je veux l'adapter à ma voiture.** Le code est dans ce dépôt et le [README](README.md) explique tout, y compris
comment fabriquer le modèle 3D.

## Crédits

Modèle 3D : « Peugeot 308 5-Door MK1 2007-2021 » par Merc_TV (szymonpasterczyk) sur Sketchfab, licence
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), modifié (pièces choisies, roues, couleurs, allègement).
Lanceur 308 n'est lié ni à Peugeot ni à aucun fabricant d'autoradio.
