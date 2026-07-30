# 01 — Schéma des datapacks d'objectifs

> Namespace du mod : `bingo` · Dossier : `data/<namespace>/objectives/**.json`
> Chargement : `SimpleSynchronousResourceReloadListener` côté serveur, rechargeable via `/reload`.

---

## 1. Vue d'ensemble des fichiers

| Chemin | Rôle |
|---|---|
| `data/<ns>/objectives/**/*.json` | Un fichier = un objectif. Sous-dossiers libres (organisation seule). |
| `data/<ns>/pools/*.json` | Ensemble d'objectifs éligibles au tirage. |
| `data/<ns>/difficulties/*.json` | Profil de distribution des niveaux sur les 25 cases. |
| `data/<ns>/rulesets/*.json` | Préréglage de partie (durée, victoire, vocal, pool). |
| `data/<ns>/tags/items/roll_decoys.json` | Leurres de l'animation de tirage (`docs/04` §3). |
| `data/<ns>/cards/` | **Réservé** — cartes pré-écrites pour parties scriptées. Schéma non spécifié, hors périmètre des lots 0 à 5. |

`<ns>` est le namespace du datapack. Le mod livre les siens sous `bingo`, donc `data/bingo/objectives/…`, mais **n'importe quel datapack tiers peut en ajouter** sous son propre namespace — c'est tout l'intérêt de passer par le système de datapacks.

**Construction de l'ID** : `data/bingo/objectives/craft/iron_pickaxe.json` → `bingo:craft/iron_pickaxe`. Le namespace vient du dossier après `data/`, le path est le chemin relatif après le dossier de registre, sans l'extension.

Côté implémentation, `ResourceFinder`/`manager.findAllResources("objectives", …)` scanne `data/*/objectives/` sur tous les namespaces d'un coup.

---

## 2. Schéma d'un objectif

```jsonc
{
  // ── Obligatoire ────────────────────────────────────────────────
  "type": "bingo:craft",        // CRAFT | FIND | KILL_MOB | DEATH | ACTION
  "level": 2,                   // 1..5 — pilote le multiplicateur de score 2^(level-1)
  "target": { },                // charge utile spécifique au type (§4)

  // ── Affichage ──────────────────────────────────────────────────
  "display": {
    "icon": "minecraft:iron_pickaxe",             // OBLIGATOIRE — item id, rendu dans la case
    "icon_count": 1,                              // optionnel, badge numérique sur l'icône
    "name": { "translate": "bingo.obj.craft.iron_pickaxe" },
    "description": { "translate": "bingo.obj.craft.iron_pickaxe.desc" }
  },

  // ── Tirage ─────────────────────────────────────────────────────
  "weight": 10,                 // défaut 10 — poids relatif au sein de son niveau
  "tags": ["bingo:overworld", "bingo:crafting"],
  "conflicts": ["bingo:craft/iron_sword"],        // jamais sur la même carte
  "requires_dimension": null,                     // "minecraft:the_nether" pour filtrer

  // ── Comportement ───────────────────────────────────────────────
  "count": 1,                   // défaut 1 — nombre d'occurrences à atteindre
  "points_base": null,          // défaut : hérité du ruleset (100). Override par objectif.
  "interaction": null,          // défaut dérivé du type (§5). "jei" | "tooltip" | "none"
  "jei_role": null,             // défaut dérivé du type. "output" | "input"
  "announce": true              // annonce en chat à la validation
}
```

### Champs obligatoires

`type`, `level`, `target`, `display.icon`. Tout le reste a une valeur par défaut.

### Règles de validation au chargement (à implémenter, échec = objectif ignoré + log WARN)

1. `type` appartient au registre des types connus.
2. `1 <= level <= 5`.
3. `display.icon` est un item existant dans le registre (sinon fallback `minecraft:barrier` + WARN).
4. `count >= 1`.
5. `target` valide le sous-schéma du `type` (§4).
6. `conflicts` référence des IDs existants (sinon WARN, entrée ignorée).

> **Pourquoi `display.icon` est un item et jamais une entité** : le HUD ne connaît qu'un seul chemin de rendu (`DrawContext#drawItem`) et JEI n'accepte qu'un `ITypedIngredient` — pour `KILL_MOB` on utilise donc l'œuf d'apparition (`minecraft:creeper_spawn_egg`). Un seul chemin de rendu, zéro cas particulier.

---

## 3. Les 5 niveaux de difficulté

| Niveau | Nom | Multiplicateur | Intention de design | Temps cible |
|---|---|---|---|---|
| 1 | Trivial | ×1 | Faisable sans quitter le spawn. Sert à débloquer les lignes. | < 2 min |
| 2 | Standard | ×2 | Une boucle courte : miner, crafter, tuer un mob commun. | 2–8 min |
| 3 | Engagé | ×4 | Nécessite un déplacement, une structure ou une ressource rare. | 8–20 min |
| 4 | Extrême | ×8 | Nether/End, boss, ou chaîne de craft longue. Le pari risqué. | > 20 min |
| 5 | — | ×16 | **Réservé, aucun objectif livré.** Palier ouvert pour les objectifs plus durs que N4. | — |

Le **niveau n'est pas une estimation de temps** mais un contrat de score : viser la cohérence du multiplicateur, pas la précision du chrono.

Le niveau 5 existe dans le schéma et dans le tirage, mais aucun objectif ni aucun profil livré ne l'utilise : les 4 profils déclarent `"5": 0`. Un profil qui demanderait des cases N5 sans objectif correspondant serait comblé par le niveau voisin avec un WARN, comme pour tout niveau sous-alimenté.

---

## 4. Les 5 types d'objectifs

### 4.1 `bingo:craft`

**Sémantique** : le joueur fabrique l'item. Déclenché par `CraftingResultSlot` / event de craft, **pas** par la simple possession.

```jsonc
"target": {
  "item": "minecraft:iron_pickaxe",   // ou "tag": "#minecraft:planks"
  "match_nbt": false                  // défaut false
}
```

**Hook d'implémentation** : mixin sur `CraftingResultSlot#onTakeItem` + `PlayerEvents` pour four/fumoir/enclume/table de forge selon les besoins. Attention : le craft depuis l'inventaire 2×2 doit compter.

**Piège connu** : le shift-clic sur un résultat de craft produit N items en un seul événement. `count` doit s'incrémenter de `stack.getCount()`, pas de 1.

| Niveau | Exemple |
|---|---|
| 1 | `minecraft:crafting_table` |
| 2 | `minecraft:iron_pickaxe` |
| 3 | `minecraft:diamond_chestplate` |
| 4 | `minecraft:enchanting_table` |

---

### 4.2 `bingo:find`

**Sémantique** : l'item est **présent dans l'inventaire** du joueur (n'importe quel slot, y compris armure et main gauche). La validation est un scan périodique, pas un événement.

```jsonc
"target": {
  "item": "minecraft:ancient_debris",  // ou "tag": "#minecraft:coals"
  "match_nbt": false
}
```

**Hook d'implémentation** : scan toutes les **10 ticks** (0,5 s) de l'inventaire des joueurs en partie. Ne scanner que les objectifs `FIND` non validés par l'équipe du joueur — sinon c'est 25 × N joueurs × 4 fois/seconde.

**Optimisation obligatoire** : maintenir par équipe un `Set<Item>` des items encore recherchés. Le scan devient un test d'appartenance sur les stacks de l'inventaire, pas une boucle sur les objectifs.

**Piège connu** : `FIND` sur un item craftable est validable par le craft — c'est voulu. `FIND` ≠ « trouver dans un coffre », c'est « obtenir ». Nommer les objectifs en conséquence côté traductions.

| Niveau | Exemple |
|---|---|
| 1 | `minecraft:dirt` |
| 2 | `minecraft:iron_ingot` |
| 3 | `minecraft:ender_pearl` |
| 4 | `minecraft:ancient_debris` |

---

### 4.3 `bingo:kill_mob`

**Sémantique** : le joueur tue l'entité. Le crédit va au joueur si `entity.getRecentDamageSource().getAttacker()` est ce joueur, ou si le joueur est le dernier attaquant dans les 5 s (gère les chutes et la lave provoquées).

```jsonc
"target": {
  "entity_type": "minecraft:creeper",       // ou "tag": "#minecraft:skeletons"
  "require_weapon": null,                    // optionnel : "minecraft:bow"
  "max_distance": null                       // optionnel : kill à distance minimale (blocs)
}
```

**Hook d'implémentation** : `ServerLivingEntityEvents.AFTER_DEATH` (Fabric API).

**Piège connu** : les mobs tués par un loup apprivoisé ou une flèche à retardement. Décision : on **accepte** le crédit indirect via l'attaquant du `DamageSource` (`getAttacker()` remonte le propriétaire du projectile). On **refuse** le crédit sur mort naturelle (noyade, feu de camp sans intervention).

| Niveau | Exemple |
|---|---|
| 1 | `minecraft:zombie` |
| 2 | `minecraft:creeper` |
| 3 | `minecraft:blaze` |
| 4 | `minecraft:wither` |

---

### 4.4 `bingo:death`

**Sémantique** : le joueur **meurt** de la cause indiquée. Objectif volontairement à double tranchant : il coûte le stuff, d'où un niveau souvent élevé pour son temps réel.

```jsonc
"target": {
  "damage_type": "minecraft:lava",     // ou "damage_tag": "#minecraft:is_fire"
  "any_death": false                    // true = n'importe quelle mort
}
```

En 1.20.1, les types de dégâts sont un **registre dynamique** (`DamageTypes`, depuis 1.19.4) : `damage_type` doit résoudre une `RegistryKey<DamageType>` et se comparer via `source.isOf(key)` / `source.isIn(tag)`.

**Hook d'implémentation** : `ServerLivingEntityEvents.AFTER_DEATH` filtré sur `entity instanceof ServerPlayerEntity`.

**Piège connu** : le keepInventory et le respawn. Le crédit se calcule **avant** le respawn, dans l'event de mort. Et si la partie tourne en hardcore/spectateur à la mort, un objectif `DEATH` peut sortir un joueur de la partie — d'où le garde-fou ci-dessous.

> **Garde-fou obligatoire** : si le ruleset a `elimination_on_death: true`, le loader **rejette** les objectifs de type `DEATH` avec un log WARN. Les deux règles sont incompatibles.

| Niveau | Exemple |
|---|---|
| 1 | Chute (`minecraft:fall`) |
| 2 | Noyade (`minecraft:drown`) |
| 3 | Lave (`minecraft:lava`) |
| 4 | Explosion de Wither Skeleton / `minecraft:sonic_boom` (Warden) |

---

### 4.5 `bingo:action`

**Sémantique** : type fourre-tout adossé à un **registre de déclencheurs** codés en Java. C'est la soupape du système : tout ce qui n'entre pas dans les 4 autres types passe par là.

```jsonc
"target": {
  "trigger": "bingo:sleep_in_bed",   // ID dans le registre des ActionTrigger
  "params": { }                       // libre, interprété par le trigger
}
```

Variante raccourcie adossée aux advancements vanilla (aucun code à écrire) :

```jsonc
"target": {
  "trigger": "bingo:advancement",
  "params": { "advancement": "minecraft:nether/find_fortress" }
}
```

**Registre de déclencheurs minimal du lot 1** :

| Trigger | Params | Hook |
|---|---|---|
| `bingo:advancement` | `advancement` | `PlayerAdvancementTracker` / mixin sur `grantCriterion` |
| `bingo:sleep_in_bed` | — | `EntitySleepEvents.STOP_SLEEPING` |
| `bingo:enter_dimension` | `dimension` | `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` |
| `bingo:enchant_item` | `enchantment`, `min_level` | mixin `EnchantmentScreenHandler` |
| `bingo:trade_with_villager` | `profession` (opt.) | mixin `MerchantScreenHandler` |
| `bingo:tame_animal` | `entity_type` | `EntityType` + mixin `TameableEntity#setOwner` |
| `bingo:reach_y_level` | `y`, `comparator` (`"below"`/`"above"`) | tick joueur, throttle 20 ticks |
| `bingo:use_item_on_block` | `item`, `block` | `UseBlockCallback` |

`bingo:advancement` couvre à lui seul une centaine d'objectifs potentiels sans une ligne de Java. **Le privilégier systématiquement** — n'écrire un trigger dédié que si aucun advancement ne correspond.

| Niveau | Exemple |
|---|---|
| 1 | `bingo:sleep_in_bed` |
| 2 | `bingo:tame_animal` (loup) |
| 3 | `bingo:enter_dimension` (Nether) |
| 4 | `bingo:advancement` → `minecraft:end/kill_dragon` |

---

## 5. Interaction par défaut (routage du clic)

Le champ `interaction` est dérivé du `type` sauf override explicite :

| Type | `interaction` | `jei_role` | Effet du clic sur la case |
|---|---|---|---|
| `CRAFT` | `jei` | `output` | JEI : recettes **produisant** l'item |
| `FIND` | `jei` | `output` | JEI : recettes produisant l'item (fallback tooltip si aucune) |
| `KILL_MOB` | `tooltip` | — | Pop-up de description |
| `DEATH` | `tooltip` | — | Pop-up de description |
| `ACTION` | `tooltip` | — | Pop-up de description |

Détail d'implémentation en §3 du document HUD/JEI.

---

## 6. Pool d'objectifs

`data/bingo/pools/default.json`

```jsonc
{
  "display_name": { "translate": "bingo.pool.default" },
  "entries": [
    { "objective": "bingo:craft/iron_pickaxe" },
    { "objective": "bingo:find/ender_pearl", "weight": 20 }   // override du poids
  ],
  "include_tags": ["bingo:overworld"],   // ajoute tous les objectifs portant ces tags
  "exclude_tags": ["bingo:experimental"], // retire, priorité sur include
  "inherit": []                           // composition : IDs d'autres pools
}
```

Résolution : `entries` ∪ (objectifs matchant `include_tags`) ∪ (pools de `inherit`), **moins** tout ce qui matche `exclude_tags`.

---

## 7. Profil de difficulté

`data/bingo/difficulties/normal.json`

```jsonc
{
  "display_name": { "translate": "bingo.difficulty.normal" },
  "pool": "bingo:default",
  "distribution": { "1": 8, "2": 9, "3": 6, "4": 2 },   // somme = 25 OBLIGATOIRE
  "time_limit_seconds": 3600,
  "ruleset": "bingo:classic"
}
```

### Les 4 profils livrés

| Profil | N1 | N2 | N3 | N4 | N5 | Somme | Score max théorique* |
|---|---|---|---|---|---|---|---|
| `easy` | 12 | 9 | 4 | 0 | 0 | 25 | 4 600 |
| `normal` | 8 | 9 | 6 | 2 | 0 | 25 | 6 600 |
| `hard` | 4 | 8 | 9 | 4 | 0 | 25 | 8 800 |
| `extreme` | 2 | 5 | 10 | 8 | 0 | 25 | 11 600 |

\* Somme de `100 × 2^(level-1)` sur les 25 cases. Sert au calibrage relatif, pas à un objectif de jeu (personne ne complète 25 cases).

**Validation au chargement** : si `Σ distribution ≠ 25`, refuser le profil avec un log ERROR explicite. Si le pool ne contient pas assez d'objectifs d'un niveau donné, **combler avec le niveau le plus proche** et logger WARN — ne jamais crasher ni produire une grille incomplète.

---

## 8. Ruleset

`data/bingo/rulesets/classic.json`

```jsonc
{
  "display_name": { "translate": "bingo.ruleset.classic" },
  "board": { "width": 5, "height": 5, "shared_card": true },
  "win_conditions": ["line", "column", "diagonal"],
  "points_base": 100,
  "line_bonus": 0,              // bonus à la complétion d'une combinaison (défaut 0, cf. docs/05 §2.3)
  "team_size": 2,
  "max_teams": 4,
  "tile_lock": false,               // false = toutes les équipes peuvent valider la même case
  "elimination_on_death": false,    // incompatible avec les objectifs DEATH (§4.4)
  "reveal_opponent_progress": true,
  "roll_animation": true,
  "freeze_during_roll": true,
  "voice": {
    "enabled": true,
    "lobby_mode": "global",         // global | proximity
    "round_mode": "team_open"       // team_open | team_isolated | proximity
  },
  "timings": {
    "countdown_seconds": 5,
    "roll_ticks": 60,          // ⚠️ la timeline de docs/04 est calibrée pour 60 (3 s)
    "time_limit_seconds": 3600 // repli seulement — voir la précédence ci-dessous
  }
}
```

### Précédence de `time_limit_seconds`

Trois endroits peuvent définir la durée d'une manche. L'ordre est **strict** :

1. **Profil de difficulté** (`difficulties/<id>.json`) — priorité maximale.
2. **Ruleset** (`rulesets/<id>.json` → `timings.time_limit_seconds`) — utilisé si le profil ne le définit pas.
3. **Config serveur** (`/bingo config set time_limit_seconds`, défaut 3600) — dernier repli.

C'est pour cette raison que les 4 profils livrés annoncent 2700 / 3600 / 5400 / 7200 alors que `bingo:classic` porte 3600 : le profil gagne, et la valeur du ruleset ne sert que pour un profil qui l'omettrait.

### `roll_ticks` et la timeline d'animation

`roll_ticks` est exposé mais **la timeline de `docs/04` est calibrée en dur pour 60 ticks / 3 000 ms**. Une valeur différente exige de recalculer les 5 instants de verrouillage. Tant que ce n'est pas fait, traiter `roll_ticks` comme une constante et non comme un réglage.

---

## 9. Décisions actées

- **Carte partagée** : les 25 objectifs sont identiques pour toutes les équipes.
- **Pas de verrouillage** (`tile_lock: false`) : chaque équipe coche sa propre grille sur les mêmes objectifs. Deux équipes peuvent valider la même case.
- **Difficulté = profil de distribution mixte** : les 4 niveaux coexistent sur une carte, ce qui donne son sens à la formule de score.

Conséquence directe sur le modèle de données : l'état de complétion n'est **pas** porté par la case mais par le couple `(équipe, index de case)`.

```java
// Une seule carte
Objective[] tiles;                       // 25 entrées, immuable après le tirage
// Un état par équipe
Map<TeamId, BitSet> completion;           // 25 bits par équipe
Map<TeamId, long[]> completionTimestamps; // pour les égalités et le journal
```
