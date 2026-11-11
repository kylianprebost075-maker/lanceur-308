# Participer à Lanceur 308

Merci de t'intéresser au projet. Il est tenu par une seule personne, qui n'est pas développeuse et qui travaille avec
une IA : les messages simples, en français, sont les bienvenus.

## Signaler un problème

Ouvre un ticket sur la page [Issues](https://github.com/kylianprebost075-maker/lanceur-308/issues) avec :

- ta voiture (marque, modèle, année, moteur) et ton autoradio (marque, version d'Android) ;
- la version de l'appli (page Releases, ou le nom du fichier installé) ;
- ce que tu as fait, ce que tu attendais, ce qui s'est passé ;
- si possible, les lignes utiles du journal que l'appli écrit sur la clé USB
  (`Android/data/fr.p308.lanceur/files/journal/`), ou le fichier du dossier `plantages` si l'appli a planté.

**Ne publie jamais** ta plaque, ton numéro de série (VIN), tes trajets GPS ni le dossier entier de la clé.

## Dire que ça marche sur ta voiture

C'est l'aide la plus utile : l'appli n'a roulé que sur une Peugeot 308. Ouvre un ticket « Ça marche sur… » avec ta
voiture, ton autoradio, ton boîtier OBD, et ce qui marche ou non (portes, phares, données moteur). Le plus simple :
une capture de Véhicule → **Mon autoradio**, contact mis (elle ne montre aucune donnée personnelle). Si tu as adapté
`CarProfile.kt`, colle les valeurs : elles serviront aux suivants.

## Proposer une modification

1. Lis [ADAPTER.md](ADAPTER.md) (adapter à une voiture) ou le [README](README.md) (tout le reste).
2. Garde la modification petite et explique-la en une phrase.
3. Lance les tests avant d'envoyer : `gradlew testDebugUnitTest` dans `projet/lanceur-308`. Ajoute un test si tu
   touches à un calcul.
4. Dis ce que tu as vérifié, et sur quoi (émulateur, vraie voiture) : une modification jamais essayée sur une voiture
   doit le dire.

Règles du projet, non négociables :

- **Lecture seule sur la voiture.** Pas d'écriture dans les calculateurs, pas de codage, pas d'actionneurs. La seule
  exception est l'effacement des codes défaut, sur demande et après confirmation.
- **Aucune donnée personnelle** dans le dépôt : ni plaque, ni VIN, ni journaux, ni captures qui en montrent.
- **Pas d'accès à Internet** dans l'appli.
- Le code d'autres projets ne se copie que si leur licence le permet, avec le crédit.

## Licence

Tous les droits sont réservés à l'auteur : voir [LICENSE.md](LICENSE.md). En proposant une modification, tu acceptes
qu'elle soit intégrée au projet et publiée aux mêmes conditions. Les crédits sont dans [CREDITS.md](CREDITS.md) ; le
modèle 3D est sous CC BY 4.0.
