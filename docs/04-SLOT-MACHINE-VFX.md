# 04 — Animation « Slot Machine » du tirage

> Durée totale : **3 000 ms** (60 ticks) · Phase de jeu : `ROLLING`
> Animation **100 % client**, pilotée par un unique paquet serveur.

---

## 1. Principe : déterminisme par seed

Le serveur tire la carte, puis envoie **un seul paquet** sur le canal `bingo:roll_start` (`docs/06` §3.1) contenant :

- les 25 IDs d'objectifs finaux ;
- un `long seed` ;
- le timestamp de départ (`long startTimeMs`, horloge serveur).

Chaque client rejoue l'animation localement avec `new Random(seed)`. Résultat : **tous les clients voient exactement la même séquence d'icônes défiler**, pour le coût d'un seul paquet. Aucun paquet par frame, aucune bande passante pendant l'animation.

> **Compromis assumé** : le client connaît la carte finale dès `t=0`. Un joueur techniquement motivé pourrait la lire avant la fin de l'animation. Alternative envisagée puis écartée — n'envoyer la carte qu'à `t=3000` — parce qu'elle rend le reveal dépendant de la latence : un joueur à 200 ms de ping verrait ses cases se verrouiller en retard, ce qui casse la synchronisation du moment collectif. Sur un jeu entre amis, la triche théorique coûte moins cher qu'un reveal désynchronisé.

### `roll_ticks` est une constante, pas un réglage

Le ruleset expose `timings.roll_ticks: 60`, cohérent avec les 3 000 ms de cette timeline. **Tous les seuils ci-dessous sont calibrés en dur pour cette valeur.** Changer `roll_ticks` sans recalculer les 5 instants de verrouillage produit une animation incohérente — traiter la clé comme documentaire jusqu'à ce qu'un calcul proportionnel soit écrit.

### Base de temps

Utiliser `Util.getMeasuringTimeMs()`, **pas** un compteur de ticks : l'animation doit rester fluide à 144 fps et ne pas saccader lors d'un lag serveur.

```java
long elapsed = Util.getMeasuringTimeMs() - rollStartMs;   // 0 → 3000
```

Les *déclenchements sonores* sont en revanche indexés sur des seuils en ms, avec un curseur `lastFiredIndex` pour garantir un son et un seul par seuil même si une frame est sautée.

---

## 2. Timeline

```
t (ms)   0        500       1000      1500      2000  2100 2290 2500 2730 2980 3000
         │─────────────────────────────────────────│─────┼────┼────┼────┼────┼─│
         │        PHASE A — DÉFILEMENT RAPIDE      │  PHASE B — VERROUILLAGE   │
         │        25 cases cyclent ensemble        │  ligne par ligne, ralenti │
         │        swap / 100 ms                    │  swap 100 → 320 ms        │
         │        click ×20, pitch 1.6, vol 0.25   │  click à chaque verrou    │
         │                                         │                           │
  lignes ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│                           │
    L0                                             ████ verrouillée à 2100     │
    L1                                                  ████ 2290              │
    L2                                                        ████ 2500        │
    L3                                                             ████ 2730   │
    L4                                                                ████ 2980│
                                                                               │
  finale                                                    ui.toast.challenge_complete
                                                            + particules ▲ 3000
```

### 2.1 Phase A — 0 → 2 000 ms

| Paramètre | Valeur |
|---|---|
Cases concernées | les 25, simultanément |
Intervalle de swap | **100 ms** fixe (20 swaps) |
Source des icônes | pool de leurres (§3) |
Son | `block.comparator.click` |
— pitch | **1.6** fixe |
— volume | **0.25** |
— cadence | à chaque swap, soit 10 sons/s |

`volume 0.25` n'est pas un détail : à 10 sons par seconde pendant 2 s, un volume 1.0 est agressif au casque, et la moitié des joueurs sont en vocal. C'est un réglage de confort autant que de mixage.

**Rendu du swap** : pas de translation verticale, un simple remplacement d'icône. Une vraie machine à sous ferait défiler les icônes en Y, mais dans une case de 18 px le scroll est illisible et coûte un clipping par case. Le remplacement sec lit mieux et va plus vite à écrire.

### 2.2 Phase B — 2 000 → 3 000 ms

Cinq verrouillages, espacés de façon croissante pour donner la sensation de ralentissement mécanique :

| Ligne | `t` de verrouillage | Écart | Pitch du click | Volume |
|---|---|---|---|---|
L0 (haut) | **2 100 ms** | — | 1.00 | 0.7 |
L1 | **2 290 ms** | +190 | 1.20 | 0.7 |
L2 | **2 500 ms** | +210 | 1.40 | 0.7 |
L3 | **2 730 ms** | +230 | 1.60 | 0.7 |
L4 (bas) | **2 980 ms** | +250 | 1.80 | 0.8 |

Le pitch **monte** de 1.0 à 1.8 : la tension augmente alors que le rythme ralentit. Un pitch descendant donnerait une impression de panne plutôt que de suspense.

**Intervalle de swap des lignes encore libres** — ease-out sur la phase :

```java
float p = clamp((elapsed - 2000) / 1000f, 0f, 1f);   // 0 → 1
float eased = 1f - (1f - p) * (1f - p);              // ease-out quad
int swapInterval = (int) lerp(100, 320, eased);      // 100 ms → 320 ms
```

**Effet de verrouillage** (par ligne, 200 ms) :

1. `t+0` : les 5 cases prennent leur icône définitive.
2. `t+0 → t+120` : flash blanc en overlay, alpha `0xB0 → 0x00`.
3. `t+0 → t+200` : « punch » d'échelle sur les 5 cases, `1.35 → 1.0`, ease-out cubique. Rendu via `matrices.push/scale/pop` autour du centre de chaque case.
4. `t+200` : la ligne est figée et ne bougera plus.

### 2.3 Finale — 3 000 ms

| Élément | Valeur |
|---|---|
Son | `ui.toast.challenge_complete`, pitch **1.0**, volume **1.0**, catégorie `SoundCategory.PLAYERS` |
Particules monde | `ParticleTypes.FIREWORK`, **40** particules, sphère de rayon 1,5 bloc, centrée à `player.getPos().add(0, 1.2, 0)`, vitesse `±0.15` sur les 3 axes |
Étincelles HUD | 24 sprites 2D, durée 600 ms (§4) |
Fin de phase | `ROLLING` → `COUNTDOWN` à `t = 3000` |

**Les particules sont spawnées par le serveur**, via `ServerWorld#spawnParticles`, une fois par joueur en partie. Un spawn client-local serait moins coûteux mais chaque joueur ne verrait que ses propres feux d'artifice — or l'intérêt du moment est de voir ceux des autres. Le coût réseau est négligeable : un burst, une fois par manche.

```java
for (ServerPlayerEntity p : game.participants()) {
    p.getServerWorld().spawnParticles(
        p, ParticleTypes.FIREWORK,
        true,                                  // force (visible même en particules "minimal")
        p.getX(), p.getY() + 1.2, p.getZ(),
        40,                                    // count
        1.5, 1.5, 1.5,                         // delta (rayon)
        0.15                                   // speed
    );
}
```

Le flag `force = true` est important : sans lui, les joueurs réglés en particules « Minimales » ne voient rien du tout.

---

## 3. Pool de leurres

Les icônes qui défilent en phase A **ne doivent pas** être tirées au hasard dans tout le registre des items : voir défiler des blocs de commande et des œufs de dragon casse la lisibilité et spoile mal.

**Source** : un tag d'items du mod, `#bingo:roll_decoys`, dans `data/bingo/tags/items/roll_decoys.json`. **69** items « iconiques » de Minecraft, déjà livrés.

**Composition de la séquence** — 70 % leurres, 30 % vraies icônes de la carte :

```java
Random rng = new Random(seed);
// À chaque swap, pour chaque case :
Item icon = rng.nextFloat() < 0.30f
        ? finalBoard[rng.nextInt(25)].icon()   // une vraie icône, mais pas forcément la bonne
        : decoys.get(rng.nextInt(decoys.size()));
```

Mélanger de vraies icônes rend le tirage crédible — le joueur reconnaît des choses au passage — sans révéler quelle case aura quoi. Un pool 100 % leurres donne un défilement qui « sonne faux » au moment du reveal.

---

## 4. Étincelles HUD (optionnel — lot 4)

Le système de particules de Minecraft est world-space et ne peut pas dessiner dans le HUD. Pour la finale, un micro-système 2D maison :

```java
record Spark(float x, float y, float vx, float vy, long birth, int color) {}
// 24 sparks, émises du centre du panneau
// vx, vy = direction aléatoire, norme 60→140 px/s
// gravité : vy += 240 * dt
// durée 600 ms, alpha linéaire 1 → 0, taille 2×2 px
// couleurs : 0xFFFFDD44, 0xFFFF8822, 0xFFFFFFFF (tirage pondéré)
```

À implémenter **après** que le reste fonctionne. C'est du polish, pas de la mécanique.

---

## 5. Gel des joueurs pendant l'animation

Contrôlé par `freeze_during_roll` (défaut `true`).

**Méthode retenue** : modificateur d'attribut sur `GENERIC_MOVEMENT_SPEED`.

```java
private static final UUID FREEZE_UUID = UUID.fromString("...");
private static final EntityAttributeModifier FREEZE =
    new EntityAttributeModifier(FREEZE_UUID, "bingo_roll_freeze", -1.0,
                                EntityAttributeModifier.Operation.MULTIPLY_TOTAL);

// début ROLLING
player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).addTemporaryModifier(FREEZE);
// fin ROLLING (et aussi à la déconnexion, et à /bingo reset)
player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).removeModifier(FREEZE_UUID);
```

**Pourquoi pas Slowness 255** : l'effet est visible dans l'inventaire, apparaît dans le HUD des potions et interagit avec le lait. Un modificateur d'attribut est invisible et réversible proprement.

**Limite connue** : ça bloque la marche, pas le saut ni la chute. C'est suffisant pour 3 secondes. Un gel total demanderait un mixin sur `Entity#travel`, disproportionné ici.

**Garde-fou obligatoire** : retirer le modificateur dans `ServerPlayConnectionEvents.DISCONNECT` **et** au démarrage du serveur (balayage de tous les joueurs). Sinon un crash pendant `ROLLING` laisse des joueurs immobiles au rechargement du monde — bug fantôme très désagréable à diagnostiquer.

---

## 6. Machine à états de l'animation, côté client

```java
enum RollStage { IDLE, SCROLLING, LOCKING, DONE }

// LOCK_TIMES = {2100, 2290, 2500, 2730, 2980}
int lockedRows(long elapsed) {
    int n = 0;
    for (long t : LOCK_TIMES) if (elapsed >= t) n++;
    return n;                    // 0 → 5
}

boolean isRowLocked(int row, long elapsed) { return row < lockedRows(elapsed); }
```

Une case dont la ligne est verrouillée affiche `finalBoard[row * 5 + col]`. Sinon elle affiche l'icône de leurre courante. Pas de structure d'état mutable par case : tout se dérive de `elapsed`. Une animation dérivable du temps est une animation qui ne peut pas se désynchroniser.

---

## 7. Récapitulatif des sons

| `t` | Son | Pitch | Volume | Occurrences |
|---|---|---|---|---|
0 → 2000, tous les 100 ms | `block.comparator.click` | 1.6 | 0.25 | 20 |
2100 / 2290 / 2500 / 2730 / 2980 | `block.comparator.click` | 1.0 → 1.8 | 0.7–0.8 | 5 |
3000 | `ui.toast.challenge_complete` | 1.0 | 1.0 | 1 |

Tous joués via `client.getSoundManager().play(PositionedSoundInstance.master(...))` — sons non positionnels, identiques pour tous.

Aucun `.ogg` custom n'est nécessaire pour cette animation : les deux sons sont vanilla.

> ⚠️ **Chemin de fichier ≠ identifiant d'événement.** Dans `sounds.json`, la **clé** de l'objet est l'événement (`ui.countdown_tick` → `bingo:ui.countdown_tick`) tandis que `sounds[].name` (`bingo:ui/countdown_tick`) désigne le fichier `.ogg`. Le code doit toujours utiliser la forme **à points** : `SoundEvent.of(new Identifier("bingo", "ui.countdown_tick"))`. La forme à slash ne résout aucun son.

> ⚠️ **`sounds.json` n'accepte aucun commentaire.** Contrairement aux fichiers de langue, où une clé `_comment` passe pour une traduction inutilisée, le `SoundManager` désérialise **chaque** entrée de premier niveau en `SoundEntry` : une clé `_comment` portant un tableau lève `JsonSyntaxException: Expected entry to be a JsonObject`, et tout le fichier est rejeté. Le mod perd alors ses sept sons d'un coup, avec une seule ligne dans le log. Documenter ailleurs.

> **Alias vanilla plutôt que `.ogg` manquants** (tâche 4.11). Aucun `.ogg` n'est livré, mais les sept événements ne sont pas muets pour autant : chacun pointe sur un son vanilla via `{ "name": "minecraft:…", "type": "event" }`. Un fichier absent laisserait l'événement silencieux **et** produirait un avertissement à chaque chargement de ressources ; un alias donne un retour audible cohérent avec la palette du jeu, et se remplace en changeant une ligne le jour où les `.ogg` existent. Les entrées `ambient.lobby`, `voice.channel_join` et `voice.channel_leave` ont été retirées : aucune n'était enregistrée dans `BingoSounds` ni jouée nulle part.

---

## 8. Vérification manuelle

1. `/bingo start normal` → l'animation dure bien 3 s, pas 3 s ± lag.
2. Deux clients côte à côte : les icônes défilent **à l'identique** (même seed).
3. Les lignes se verrouillent de haut en bas, avec des écarts perceptiblement croissants.
4. Régler les particules sur « Minimales » : les feux d'artifice restent visibles (`force = true`).
5. Se déplacer pendant l'animation : impossible (gel actif), puis mobilité rendue à `t=3000`.
6. Tuer le serveur pendant `ROLLING`, relancer : personne n'est resté gelé.
