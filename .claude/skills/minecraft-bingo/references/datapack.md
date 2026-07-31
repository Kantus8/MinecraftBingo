# Datapack : objectifs, pools, difficultés, rulesets

Spec d'origine : `docs/01`. **Les codecs sont l'autorité** sur les champs exacts — la spec dit
l'intention, le codec dit ce qui se charge réellement. Avant d'inventer un champ, lire le codec
correspondant.

## Où vit quoi

| Donnée | JSON | Codec / loader |
|---|---|---|
| objectif | `data/bingo/objectives/<type>/*.json` (103 livrés, dont 4 en `bingo:experimental`) | `objective/Objective.java` + `objective/type/*Target.java` |
| pool | `data/bingo/pools/*.json` | `data/Pool.java`, résolution dans `data/PoolResolver.java` |
| difficulté | `data/bingo/difficulties/*.json` (5 livrées) | `data/DifficultyProfile.java` |
| ruleset | `data/bingo/rulesets/*.json` (1 livré) | `data/Ruleset.java` |
| tags d'items | `data/bingo/tags/…`, générés | `data/BingoItemTagProvider.java`, `registry/BingoItemTags.java` |

Chargement : `data/loader/ObjectiveLoader.java` (reload listener) et
`data/loader/JsonRegistryLoader.java` (le générique des trois autres registres). Point d'accès unique :
`data/BingoData.java` → `OBJECTIVES`, `POOLS`, `DIFFICULTIES`, `RULESETS`, `revision()`.

`revision()` est l'entier qui interdit au client d'afficher une carte avec un catalogue périmé
(garde-fou 2 de `docs/06` §3.4).

## Un objectif

```json
{
  "type": "bingo:craft",
  "level": 3,
  "target": { "item": "minecraft:cake" },
  "weight": 10,
  "tags": ["bingo:overworld", "bingo:crafting"],
  "display": {
    "icon": "minecraft:cake",
    "name": { "translate": "bingo.obj.craft.cake" },
    "description": { "translate": "bingo.obj.craft.cake.desc" }
  }
}
```

Champs exacts du codec (`Objective.CODEC`), pour n'avoir pas à le relire :

| Champ | Obligatoire | Défaut | Rôle |
|---|---|---|---|
| `type` | oui | — | dispatch vers le codec de `target` |
| `target` | oui | — | dépend du type (voir tableau suivant) |
| `level` | oui | — | 1 à 5, détermine le score : `pointsBase << (level − 1)` |
| `display` | oui | — | `icon` (obligatoire), `icon_count` (1), `name`, `description` |
| `weight` | non | 10 | pondération du tirage |
| `tags` | non | `[]` | inclusion/exclusion par les pools |
| `conflicts` | non | `[]` | objectifs à ne pas tirer ensemble |
| `requires_dimension` | non | — | restreint la validité à une dimension |
| `count` | non | 1 | exemplaires requis ; > 1 fait apparaître le badge `3/8` du HUD |
| `points_base` | non | ruleset | surcharge du barème pour cet objectif seul |
| `interaction` | non | dérivé du type | `jei` / `tooltip` / `none` |
| `jei_role` | non | dérivé du type | rôle de l'item dans la recette JEI |
| `announce` | non | `true` | annonce publique à la validation |

Les clés `name` et `description` sont des `Text` sérialisés — donc des `translate`, avec les clés
déclarées dans **les deux** fichiers de lang. Un objectif nommé en littéral s'afficherait en français
chez un joueur anglophone.

### Les 5 types et leur `target`

| `type` | Codec | Détection |
|---|---|---|
| `bingo:craft` | `CraftTarget` | mixin sur le craft, `amount` respecté (le shift-clic produit N items en un événement) |
| `bingo:find` | `FindTarget` | scan d'inventaire toutes les 10 ticks, 41 emplacements |
| `bingo:kill_mob` | `KillMobTarget` | `ServerLivingEntityEvents.AFTER_DEATH`, options `require_weapon` / `max_distance` |
| `bingo:death` | `DeathTarget` | mort du joueur, par `damage_type` ou tag, ou `any_death` |
| `bingo:action` | `ActionTarget` | déclencheurs de `game/detect/ActionTriggers.java` (dimension, sommeil, altitude, troc, apprivoisement, enchantement…) |

`item` et `entity` acceptent un identifiant **ou** un tag : voir `objective/condition/ItemMatcher.java`
et `EntityMatcher.java`, dont les variantes `OfTag` sont traitées à part dans les index inversés (un
prédicat de tag s'évalue par emplacement, pas par lookup de hash).

Deux pièges documentés dans le code :
- les types de dégâts sont un registre **dynamique** depuis 1.19.4 : un `damage_type` inexistant ne se
  détecte pas au chargement, il ne matche simplement jamais ;
- `max_distance` est nommé ainsi et se comporte ainsi (« au plus »), malgré un commentaire contraire
  dans `docs/01` §4.3.

### Interaction au clic

`interaction` (`jei` | `tooltip` | `none`) a un défaut **dérivé du type**, résolu côté serveur et
transporté dans `ObjectiveProjection` : le client n'a pas à connaître cette table. `CRAFT` et `FIND`
partent sur JEI, les autres sur tooltip.

## Un pool

```json
{
  "display_name": { "translate": "bingo.pool.default" },
  "entries": [],
  "include_tags": ["bingo:overworld", "bingo:nether", "bingo:end"],
  "exclude_tags": ["bingo:experimental"],
  "inherit": []
}
```

`PoolResolver` applique `inherit`, puis `entries` + `include_tags`, puis retire `exclude_tags`.

## Une difficulté

```json
{
  "display_name": { "translate": "bingo.difficulty.normal" },
  "pool": "bingo:default",
  "distribution": { "1": 8, "2": 9, "3": 8, "4": 0, "5": 0 },
  "time_limit_seconds": 3600,
  "ruleset": "bingo:classic"
}
```

`distribution` doit totaliser 25. Si le pool ne peut pas fournir la répartition demandée,
`BoardGenerator` rend un tirage incomplet : `BingoGame.start` refuse alors avec `EMPTY_BOARD` et remonte
les avertissements à l'opérateur plutôt que de démarrer une manche injouable.

### Niveau cible par ligne — la contrainte qui pilote les distributions

Chaque ligne et chaque colonne doit cumuler le même niveau à ±1 près (`BoardGenerator.LINE_TOLERANCE`).
La cible est **dérivée**, jamais déclarée : `Σ(niveau × compte) / 5`. Les 5 lignes couvrent les 25 cases,
donc une cible posée dans le JSON serait fausse dès que la distribution ne totalise pas cinq fois cette
cible.

Conséquence : **modifier une `distribution` déplace la difficulté visée.** Les 5 profils livrés visent
5 / 7 / 10 / 13 / 16, soit des totaux de 25 / 35 / 50 / 65 / 80.

| Profil | Distribution | Total | Cible/ligne |
|---|---|---|---|
| easiest | 25·N1 | 25 | 5 |
| easy | 15·N1 10·N2 | 35 | 7 |
| normal | 8·N1 9·N2 8·N3 | 50 | 10 |
| hard | 2·N1 10·N2 9·N3 4·N4 | 65 | 13 |
| extreme | 7·N2 9·N3 6·N4 3·N5 | 80 | 16 |

Le placement est fait par recherche locale (échanges de deux cases) dans la section « Équilibrage » de
`BoardGenerator` — donc sans jamais toucher à la distribution. Si la bande ±1 n'est pas atteignable, le
tirage passe quand même avec un avertissement : une grille un peu déséquilibrée reste jouable.
`/bingo debug dump <difficulté>` affiche les sommes par ligne, par colonne et par diagonale.

Les **diagonales ne sont pas contraintes**, seulement mesurées : ce sont pourtant 2 des 12 chemins de
victoire. Les forcer dans la bande ajouterait deux équations à un système qui n'a déjà pas toujours de
solution.

## Un ruleset

`Ruleset` porte `points_base`, `team_size`, `max_teams`, `reveal_opponent_progress`, `roll_animation`,
`freeze_during_roll`, les `win_conditions` (12 combinaisons possibles), un sous-objet `timings`
(`roll_ticks`, `countdown_seconds`) et un sous-objet `voice`.

## Résolution d'un réglage — l'ordre

Trois sources, du plus spécifique au plus général :

1. le **ruleset** de la manche (`BingoGame.ruleset()`) ;
2. le **profil de difficulté** — le seul cas non trivial est la limite de temps, arbitrée par
   `DifficultyProfile.effectiveTimeLimitSeconds(rules, fallback)` : lire cette méthode plutôt que
   supposer ;
3. `config/BingoServerConfig.java` en **repli**.

C'est ce que rappelle l'en-tête de `/bingo config list` : poser `points_base 200` sans effet visible
n'est pas un bug, c'est le ruleset `classic` qui fixe déjà la valeur.

Les accesseurs de `BingoGame` (`pointsBase()`, `teamSize()`, `revealOpponentProgress()`…) encapsulent
cette cascade — les appeler plutôt que relire `BingoServerConfig` directement.

## Recharger

`/bingo reload` passe par `reloadResources` (comme le `/reload` vanilla), donc rejoue tous les reload
listeners. La rediffusion du catalogue puis de la carte est branchée sur `END_DATA_PACK_RELOAD` dans
l'entrypoint, ce qui couvre aussi le `/reload` vanilla. `BingoGame.onDataReload()` réaligne la manche en
cours — ou l'abandonne proprement si un identifiant a disparu.

## Datagen

`data/BingoDataGenerator.java` (entrypoint `fabric-datagen`) produit les tags d'items.
`./gradlew runDatagen` écrit dans `src/main/generated` ; le churn dans `.cache/` est normal et se
committe.
