# Minecraft Bingo — Guide du joueur

Bingo compétitif en équipe pour **Minecraft 1.20.1 (Fabric)**. Chaque équipe reçoit la même grille 5×5 d'objectifs ; la première qui aligne une **ligne, une colonne ou une diagonale** gagne. On ne se bloque pas, on court — et on lit le HUD adverse pour savoir où l'ennemi en est.

> Ce guide s'adresse aux **joueurs et aux organisateurs de partie**. Pour construire ou modifier le mod, voir le [README développeur](README.md).

---

## 1. Installation

Le mod tourne sur **Fabric** et a besoin de trois autres mods pour fonctionner. Sans eux, le jeu refuse de démarrer.

1. Installe **[Fabric Loader](https://fabricmc.net/use/installer/)** pour Minecraft **1.20.1**.
2. Place les `.jar` suivants dans ton dossier `mods/` :
   - **Minecraft Bingo** (`bingo-….jar`)
   - **[Fabric API](https://modrinth.com/mod/fabric-api)** (0.92.9+1.20.1 ou compatible)
   - **[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)** (1.20.1-2.5.0 ou plus récent)
   - **[Just Enough Items – JEI](https://modrinth.com/mod/jei)** (15.20 ou plus récent)
3. Lance Minecraft avec le profil Fabric.

En multijoueur, **le serveur ET chaque joueur** doivent avoir ces quatre mods. Simple Voice Chat nécessite en plus que le port vocal du serveur soit ouvert (voir la doc de Simple Voice Chat).

---

## 2. Comment on joue

- **La carte est partagée** : les 25 mêmes objectifs pour toutes les équipes. Chaque équipe coche sa propre grille, et deux équipes peuvent valider la même case.
- **On gagne en alignant** 5 cases : une des 5 lignes, une des 5 colonnes, ou une des 2 diagonales — **12 combinaisons** possibles. La première équipe qui en complète une gagne **immédiatement**.
- **Cinq types d'objectifs** :
  | Type | Ce qu'il faut faire |
  |---|---|
  | **Craft** | Fabriquer l'objet |
  | **Find** | Avoir l'objet dans l'inventaire (validation automatique, pas besoin de le garder) |
  | **Kill** | Tuer la créature |
  | **Death** | Mourir de la cause indiquée |
  | **Action** | Un événement précis : dormir, entrer au Nether, apprivoiser, enchanter… |
- **Score et égalités** : le score sert au classement, pas à la victoire — celle-ci se décide sur la géométrie de la grille. Si le temps s'écoule sans alignement, l'équipe gagnante est départagée dans l'ordre : plus haut score → plus de cases → meilleure combinaison entamée (4/5 bat 3/5) → sinon match nul.

### Le HUD et l'écran de carte

- Le HUD 5×5 s'affiche en jeu et montre **ta progression et celle des adversaires** (une équipe à 4/5 sur une colonne, ça se lit — et ça se joue).
- Touche **`B`** : ouvre l'écran de carte cliquable.
  - **Clic gauche** sur une case `Craft` ou `Find` : ouvre la recette **JEI**.
  - **Clic droit** : affiche la description de l'objectif.
- Une touche **« Afficher/masquer le HUD »** existe mais n'est **pas assignée par défaut** : associe-la dans *Options → Commandes → catégorie Bingo* si tu veux masquer le HUD.

---

## 3. Commandes

Racine : `/bingo`. Les commandes marquées **[OP]** demandent le niveau opérateur (niveau 2) ; les autres sont ouvertes à tous.

### Tout le monde

| Commande | Effet |
|---|---|
| `/bingo status` | Phase en cours, chrono, nombre d'équipes |
| `/bingo score` | Classement détaillé de chaque équipe |
| `/bingo card` | Ouvre l'écran de carte (comme la touche `B`) |
| `/bingo team list` | Équipes et membres |
| `/bingo team join <équipe>` | Rejoindre une équipe (impossible en pleine manche) |
| `/bingo team leave` | Quitter son équipe (impossible en pleine manche) |

### Organisateur (opérateur) [OP]

| Commande | Effet |
|---|---|
| `/bingo team create <id> <couleur>` | Créer une équipe |
| `/bingo team remove <id>` | Supprimer une équipe |
| `/bingo team set <joueurs> <équipe>` | Affecter d'autorité des joueurs |
| `/bingo team clear` | Vider toutes les équipes |
| `/bingo team autobalance` | Répartir les joueurs sans équipe par deux |
| `/bingo start <difficulté> [ruleset]` | Lancer une manche (tirage → décompte → jeu) |
| `/bingo stop` | Terminer sans vainqueur |
| `/bingo pause` / `/bingo resume` | Figer / reprendre le chrono (et repasse le vocal en global) |
| `/bingo reset` | Tout remettre à zéro (fonctionne depuis n'importe quelle phase) |
| `/bingo reroll` | Retirer une nouvelle carte et rejouer l'animation |
| `/bingo reload` | Recharger objectifs, pools, difficultés et rulesets |
| `/bingo config list` / `get <clé>` / `set <clé> <valeur>` | Consulter et modifier les réglages serveur |

Difficultés livrées : `easy`, `normal`, `hard`, `extreme`.

> Une manche démarre à **2 équipes minimum**, chacune avec au moins un joueur. Les joueurs sans équipe au départ passent **spectateurs**. Les équipes survivent à un `stop` mais pas à un `reset`.

---

## 4. Le chat vocal

Le mod pilote automatiquement les groupes de **Simple Voice Chat** selon la phase de jeu :

- **Hors manche (lobby)** : tout le monde s'entend.
- **En manche** : chaque équipe a son **groupe vocal privé**. Tu parles à tes coéquipiers ; tu entends aussi les adversaires **proches de toi** dans le monde (proximité), ce qui fait tout le sel des rencontres.
- **En pause** : le vocal repasse en global, pour que l'arbitre puisse parler à tout le monde.

### Écrasement du groupe vocal

Pendant une manche, **le mod est maître des groupes**. Si tu changes de groupe à la main dans l'interface de Simple Voice Chat, le mod te **replace dans le groupe de ton équipe en une seconde** : c'est voulu, pour empêcher qu'on aille écouter l'équipe adverse. De même, passer en spectateur te sort proprement des groupes d'équipe.

Un organisateur peut **désactiver complètement** cette gestion — laissant Simple Voice Chat entièrement libre — avec :

```
/bingo config set voice_enabled false
```

---

## 5. Réglages

### Réglages serveur — `/bingo config` [OP]

Modifiables en jeu et persistés dans `config/bingo-server.json`. Ce sont des valeurs de **repli** : un profil de difficulté ou un ruleset peut les remplacer pour une manche donnée.

| Clé | Défaut | Effet |
|---|---|---|
| `points_base` | 100 | Points d'une case de niveau 1 (×2 par niveau) |
| `time_limit_seconds` | 3600 | Durée max d'une manche |
| `countdown_seconds` | 5 | Décompte avant le départ |
| `team_size` | 2 | Taille d'équipe |
| `max_teams` | 4 | Nombre maximal d'équipes |
| `reveal_opponent_progress` | true | Le HUD montre-t-il la progression adverse |
| `roll_animation` | true | Animation « machine à sous » au tirage |
| `freeze_during_roll` | true | Joueurs figés pendant l'animation |
| `voice_enabled` | true | Gestion des groupes vocaux par le mod |
| `announce_completions` | true | Annonce en chat à chaque validation |

### Réglages client — `config/bingo-client.json`

Propres à ton écran, jamais imposés par le serveur : marges du HUD (`hud_margin_x` / `hud_margin_y`), échelle (`hud_scale`, entre 0.75 et 1.5) et visibilité (`hud_visible`).

---

## 6. Problèmes courants

- **Le jeu ne démarre pas / « missing mod »** : il manque Fabric API, Simple Voice Chat ou JEI. Les trois sont obligatoires.
- **Le clic gauche ne fait rien sur une case Craft** : JEI n'est pas chargé, ou l'objet n'a pas de recette (les cases `Find` sans recette affichent alors une simple infobulle).
- **On n'entend pas ses coéquipiers** : vérifier que Simple Voice Chat est configuré (micro, port serveur) — le mod gère les groupes, pas le son lui-même.
- **Le vocal ne se comporte pas comme prévu** : si un organisateur a fait `voice_enabled false`, le mod ne touche plus aux groupes.
