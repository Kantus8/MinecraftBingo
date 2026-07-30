# 05 — Règles de victoire, scoring et commandes

---

## 1. Conditions de victoire

### 1.1 La règle

**La première équipe à compléter une ligne, une colonne ou une diagonale de sa grille gagne immédiatement.**

Sur une grille 5×5, cela fait **12 combinaisons gagnantes** :

```
        c0  c1  c2  c3  c4
   r0  ┌──┬──┬──┬──┬──┐     5 lignes    : r0 … r4
   r1  ├──┼──┼──┼──┼──┤     5 colonnes  : c0 … c4
   r2  ├──┼──┼──┼──┼──┤     2 diagonales: (0,6,12,18,24)
   r3  ├──┼──┼──┼──┼──┤                   (4,8,12,16,20)
   r4  └──┴──┴──┴──┴──┘     ────────────────────────────
                             TOTAL : 12
```

Les 12 combinaisons sont précalculées une seule fois en `static final int[][] WINNING_LINES` (12 × 5 index). La détection devient un test de 12 masques sur le `BitSet` de l'équipe — négligeable, appelable à chaque validation sans réflexion sur le coût.

```java
// Représentation compacte : un masque de 25 bits par ligne gagnante
static final int[] LINE_MASKS = new int[12];   // rempli au chargement de la classe

boolean hasWon(int completionMask) {
    for (int mask : LINE_MASKS) {
        if ((completionMask & mask) == mask) return true;
    }
    return false;
}
```

Avec 25 cases, l'état de complétion d'une équipe **tient dans un `int`**. Utiliser un `int` plutôt qu'un `BitSet` : sérialisation triviale sur le réseau (4 octets), comparaison en une instruction.

### 1.2 Ce que la règle implique

Rappel des décisions actées (`docs/01` §9) : **carte partagée**, **pas de verrouillage de case**. Chaque équipe coche sa propre grille sur les 25 mêmes objectifs, et deux équipes peuvent valider la même case.

Conséquence de design : les équipes ne se bloquent pas, elles courent. La confrontation vient de l'information — voir sur le HUD que l'équipe adverse a validé 4 cases de la colonne 2 dit exactement où elle va, et c'est ce qui déclenche les décisions en vocal (« ils vont finir c2, on abandonne notre ligne et on fonce sur la diagonale »).

### 1.3 Fin par temps écoulé

Si `time_limit_seconds` expire sans qu'aucune équipe n'ait complété de combinaison :

1. L'équipe au **score le plus élevé** gagne.
2. Égalité → l'équipe au **plus grand nombre de cases** validées.
3. Égalité → l'équipe dont la **meilleure combinaison est la plus avancée** (4/5 bat 3/5).
4. Égalité → **match nul** déclaré.

### 1.4 Égalité sur la ligne

Deux équipes peuvent valider leur 5ᵉ case dans le même tick (deux joueurs qui craftent simultanément).

Ordre de résolution :

1. **Timestamp de validation** en millisecondes (`System.currentTimeMillis()` au moment du traitement serveur). C'est ce qui tranche dans 99 % des cas.
2. Timestamps identiques → **score le plus élevé**.
3. Toujours égalité → **victoire partagée**, les deux équipes sont déclarées gagnantes.

> Le timestamp est pris **côté serveur, au traitement de l'événement** — jamais côté client. Sinon la latence décide du vainqueur.

---

## 2. Calcul du score

### 2.1 Formule

$$\text{Score}_{\text{case}} = \text{PointsBase} \times 2^{(\text{Niveau} - 1)}$$

Avec `PointsBase = 100` par défaut (`ruleset.points_base`, surchargeable par objectif via `points_base`) :

| Niveau | Multiplicateur | Points |
|---|---|---|
| 1 — Trivial | ×1 | **100** |
| 2 — Standard | ×2 | **200** |
| 3 — Engagé | ×4 | **400** |
| 4 — Extrême | ×8 | **800** |

```java
public static int tileScore(Objective obj, int rulesetPointsBase) {
    int base = obj.pointsBase().orElse(rulesetPointsBase);
    return base << (obj.level() - 1);        // 2^(level-1) sans pow()
}
```

`base << (level - 1)` est exact et évite un `Math.pow` en double suivi d'un cast — pas pour la performance, mais parce qu'un score entier doit rester entier sans arrondi possible.

### 2.2 Score d'équipe

$$\text{Score}_{\text{équipe}} = \sum_{\text{cases validées}} \text{Score}_{\text{case}}$$

Recalculé de zéro à chaque validation depuis le masque de complétion, jamais accumulé de façon incrémentale. Un score dérivé de l'état ne peut pas dériver de l'état ; un score incrémenté peut se désynchroniser sur une annulation ou un rechargement.

### 2.3 Bonus de combinaison — optionnel, désactivé par défaut

`ruleset.line_bonus` (défaut `0`). Si > 0, une équipe qui complète une combinaison gagnante reçoit ce bonus. Comme la partie s'arrête à ce moment-là, le bonus n'a d'effet que sur l'affichage final et sur les égalités de §1.3 — d'où le défaut à 0.

Volontairement laissé en option : ça n'apporte rien à la boucle de jeu et ça complique la lecture du score. À n'activer que si un mode « partie longue sans victoire immédiate » est ajouté plus tard.

### 2.4 Le score ne détermine pas la victoire

Point important : le score sert au **classement**, aux **égalités** et au **ressenti de progression**. La victoire est décidée par la géométrie de la grille, pas par les points. Une équipe peut gagner avec 5 cases de niveau 1 (500 pts) contre une équipe à 8 cases éparpillées (3 200 pts).

C'est intentionnel : ça oblige à choisir entre « prendre les cases faciles alignées » et « prendre les cases rentables », et c'est de cette tension que naissent les décisions intéressantes.

---

## 3. Composition des équipes

| Règle | Valeur | Source |
|---|---|---|
Taille d'équipe | **2** | `ruleset.team_size` |
Nombre d'équipes | 2 à 4 | `ruleset.max_teams` |
Équipe incomplète au `start` | autorisée, avec avertissement en chat | — |
Changement d'équipe en manche | **interdit** | — |
Joueur sans équipe au `start` | passe **spectateur** | — |

Une équipe = un `TeamId` (chaîne, ex. `red`), un nom affiché traduisible, une `Formatting` de couleur, un `Set<UUID>` de membres.

**Persistance** : les équipes survivent à un `/bingo stop` mais pas à un `/bingo reset`. Utile pour enchaîner plusieurs manches avec les mêmes binômes.

---

## 4. Commandes

Racine : `/bingo`. Niveaux de permission Brigadier — **0** = tout joueur, **2** = opérateur.

### 4.1 Arbre complet

```
/bingo
├── status                                   [0]  phase, chrono, scores
├── score                                    [0]  détail du score de chaque équipe
├── card                                     [0]  ouvre BingoBoardScreen
│
├── team
│   ├── list                                 [0]  équipes et membres
│   ├── join <team>                          [0]  rejoint si non pleine
│   ├── leave                                [0]  quitte (interdit en RUNNING)
│   ├── create <id> <color>                  [2]  crée une équipe
│   ├── remove <id>                          [2]  supprime
│   ├── set <players> <team>                 [2]  affecte d'autorité
│   ├── clear                                [2]  vide toutes les équipes
│   └── autobalance                          [2]  répartit les joueurs sans équipe par 2
│
├── start <difficulty> [ruleset]             [2]  ROLLING → COUNTDOWN → RUNNING
├── stop                                     [2]  → FINISHED, sans vainqueur
├── pause                                    [2]  RUNNING → PAUSED (chrono figé)
├── resume                                   [2]  PAUSED → RUNNING
├── reset                                    [2]  tout remettre à zéro, → LOBBY
│
├── reroll                                   [2]  retire une nouvelle carte (rejoue l'animation)
├── reload                                   [2]  recharge objectifs/pools/difficultés/rulesets
│
├── config
│   ├── list                                 [2]
│   ├── get <key>                            [2]
│   └── set <key> <value>                    [2]
│
└── debug                                    [2]
    ├── complete <team> <index>                   valide une case (0..24)
    ├── uncomplete <team> <index>
    ├── solo [difficulty]                         monte une manche jouable à un seul joueur
    ├── roll                                      rejoue l'animation sans changer la carte
    ├── voice                                     état des groupes vocaux
    ├── objectives                                état du registre d'objectifs chargé
    ├── dump <difficulty> [seed]                  tire une carte d'essai sans toucher à la partie
    └── state                                     dump de l'état de partie dans les logs
```

> **Note d'implémentation** : `dump` a pris le sens « banc d'essai du `BoardGenerator` » au lot 1,
> avant que l'état de partie n'existe. Le dump d'état annoncé ici est donc `state`.

**`/bingo debug solo [difficulty]`**

Remise à zéro, création des équipes `red` et `blue`, adhésion de l'émetteur à `red`, démarrage — et c'est le **seul** chemin qui lève la précondition des deux équipes pourvues de §4.2. Elle existe parce que la recette du lot 2 demande 4 joueurs, ce qui rend la boucle de jeu intestable pour un développeur seul.

L'équipe adverse reste **vide** : elle a son masque, son score et sa place au pied du HUD, et `/bingo debug complete blue <index>` suffit à simuler sa progression, pastilles adverses comprises. La peupler d'un membre fantôme ferait apparaître un UUID inexistant dans `team list`, dans `team_sync` et dans la sauvegarde.

Rejouable telle quelle, y compris en pleine manche. Défaut de `difficulty` : `normal`.

### 4.2 Détails des commandes principales

**`/bingo start <difficulty> [ruleset]`**

- `<difficulty>` : suggestion dynamique depuis les profils chargés (`easy`, `normal`, `hard`, `extreme`).
- `[ruleset]` : défaut = celui déclaré par le profil de difficulté.
- **Préconditions** : phase `LOBBY` ou `FINISHED` ; au moins 2 équipes avec ≥ 1 membre. Sinon échec avec message explicite (`bingo.command.error.*`), jamais un échec silencieux.
- Enchaîne : tirage de la carte → `ROLLING` (3 s) → `COUNTDOWN` (5 s) → `RUNNING`.

**`/bingo pause`**

Fige le chrono, suspend la validation des objectifs, **et repasse le vocal en global** (`docs/02` §2). C'est la commande d'arbitrage : elle permet de discuter à voix haute avec tout le monde.

**`/bingo reset`**

Remise à zéro complète : carte, scores, complétions, équipes, groupes vocaux, modificateurs de gel. C'est aussi le filet de sécurité en cas d'état incohérent — elle doit fonctionner **depuis n'importe quelle phase**, y compris `ROLLING`.

**`/bingo reroll`**

Ne s'utilise raisonnablement qu'en `COUNTDOWN` ou juste après le début de `RUNNING`, quand la carte tirée est manifestement injouable. Remet toutes les complétions à zéro et rejoue l'animation.

**`/bingo card`**

Ouvre l'écran cliquable. Purement client : la commande envoie un paquet sur le canal `bingo:open_board` (`docs/06` §3.1) au joueur émetteur. Doublon volontaire du keybind `B`, pour les joueurs qui ne connaissent pas le bind.

### 4.3 Clés de configuration exposées

| Clé | Type | Défaut | Portée |
|---|---|---|---|
`points_base` | int | 100 | serveur |
`time_limit_seconds` | int | 3600 | serveur — **repli** : le profil de difficulté est prioritaire (`docs/01` §7) |
`countdown_seconds` | int | 5 | serveur |
`team_size` | int | 2 | serveur |
`max_teams` | int | 4 | serveur |
`tile_lock` | bool | false | serveur |
`reveal_opponent_progress` | bool | true | serveur |
`roll_animation` | bool | true | serveur |
`freeze_during_roll` | bool | true | serveur |
`voice_enabled` | bool | true | serveur |
`announce_completions` | bool | true | serveur |
`hud_margin_x` / `hud_margin_y` | int | 8 / 8 | **client** |
`hud_scale` | float | 1.0 | **client** |
`hud_visible` | bool | true | **client** |

Les clés client vivent dans un fichier séparé (`config/bingo-client.json`) et ne passent jamais par `/bingo config`.

### 4.4 Messages de retour

Tous les retours passent par des clés de traduction, jamais par des littéraux. Convention :

- `bingo.command.<commande>.success`
- `bingo.command.error.<cas>`
- `bingo.message.<événement>` pour les annonces de partie

Les erreurs utilisent `SimpleCommandExceptionType` avec la clé traduite, pour que Brigadier les affiche en rouge et n'exécute pas la commande.

---

## 5. Annonces en partie

| Événement | Portée | Contenu |
|---|---|---|
Validation d'un objectif | équipe concernée | `bingo.message.objective_completed` + son `bingo:ui.objective_complete` |
Validation d'un objectif | autres équipes | même message si `reveal_opponent_progress`, sans le son |
Équipe à 4/5 sur une combinaison | **son local uniquement** pour l'équipe concernée | `bingo:ui.line_complete` |
Victoire | tous | titre plein écran + `bingo:ui.bingo` |
Fin par temps écoulé | tous | classement complet |
Dernière minute | tous | `bingo:ui.countdown_tick` sur les 10 dernières secondes |

**Ne pas annoncer publiquement le « 4/5 »** : c'est l'information la plus précieuse de la partie, et la donner gratuitement retire tout l'intérêt de lire le HUD adverse. Le HUD la révèle déjà à qui prend la peine de regarder — c'est le bon niveau de friction.
