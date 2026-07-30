# Éditeur d'objectifs

Outil hors-jeu pour préparer des modifications du bingo : ajuster les niveaux, retoucher
les poids et les quantités, écrire de nouveaux objectifs, rééquilibrer les profils de
difficulté — puis en sortir un **brief** à coller dans Claude Code.

L'outil **n'écrit rien** dans le mod. Il lit le datapack et produit du texte. L'implémentation
reste manuelle, l'outil ne fait que la cadrer et éviter les oublis (clés de langue,
`icon_count`, somme de distribution à 25, pool insuffisant pour un niveau).

## Utilisation

```bash
python tools/objective-editor/build.py --open
```

Le script lit `src/main/resources/data/bingo/{objectives,difficulties}` et les fichiers de
langue, puis écrit `tools/objective-editor/objective-editor.html` — une page autonome, sans
serveur ni dépendance, qui embarque un instantané des données.

**À relancer après chaque modification du datapack** : la page ne se met pas à jour toute
seule. Sans `--open`, le script affiche juste le chemin du fichier généré.

## Les quatre onglets

| Onglet | Ce qu'on y fait |
|---|---|
| **Objectifs** | Les 45 objectifs. Changer le niveau (1–5), le poids, le `count`, ou laisser une note libre. Marquer un objectif pour suppression. Filtres par type / niveau / tag + recherche. |
| **Nouveaux objectifs** | Formulaire complet : type, cible (champs adaptés au type), niveau, tags, icône, textes fr/en, options avancées. Chaque ajout produit un JSON prêt à écrire. |
| **Profils de difficulté** | Les 5 compteurs de chaque profil, avec contrôle de la somme (25) et alerte si un niveau demande plus de cases qu'il n'existe d'objectifs. |
| **Export** | Le brief en markdown. « Copier le brief » puis coller dans Claude Code. |

Le travail en cours est sauvegardé dans le `localStorage` du navigateur : fermer l'onglet ne
perd rien. Le brief contient aussi un bloc JSON repliable — le recoller dans « Reprendre un
brief précédent » restaure l'édition, y compris depuis une autre machine.

## Ce que le brief contient

Une section par nature de changement, avec pour chacune le fichier concerné et les valeurs
avant/après : changements de niveau, autres champs, demandes libres, nouveaux objectifs
(JSON + clés de langue fr/en), profils, suppressions. Puis le change set brut en JSON.

Les rappels que l'outil ajoute tout seul :

- un `count` modifié implique `display.icon_count` **et** le texte qui annonce la quantité ;
- une traduction anglaise absente est signalée au lieu d'être remplie avec du français ;
- une distribution hors somme 25 est refusée au chargement du profil ;
- un niveau qui demande plus de cases qu'il n'a d'objectifs sera comblé par le niveau voisin,
  avec un WARN.

## Niveau 5

Le palier N5 (×16) existe dans le schéma, le codec, le tirage et les profils, mais **aucun
objectif ne l'utilise** : les quatre profils livrés déclarent `"5": 0`. C'est le palier à
remplir via l'onglet « Nouveaux objectifs ».

Les bornes viennent de `Objective.MIN_LEVEL` / `Objective.MAX_LEVEL` — `build.py` les lit
dans le source Java, donc ouvrir un niveau 6 un jour ne demandera pas de toucher à l'outil.

## Fichiers

| Fichier | Rôle |
|---|---|
| `build.py` | Scanne le datapack, injecte l'instantané dans le template. |
| `template.html` | L'application. C'est **ici** qu'on modifie l'outil. |
| `objective-editor.html` | Généré. Ne pas éditer à la main, `build.py` l'écrase. |
