# Brief de modification du bingo

Généré par `tools/objective-editor` depuis l'instantané du 2026-07-30 13:32.
34 changement(s) demandé(s). Applique-les au datapack et au code, puis vérifie que ça compile.

## 1. Changements de niveau

| Objectif | Fichier | Actuel | Nouveau |
|---|---|---|---|
| Enchanter un objet (`bingo:action/enchant_item`) | `src/main/resources/data/bingo/objectives/action/enchant_item.json` | N3 | **N4** |
| Entrer dans l'End (`bingo:action/enter_end`) | `src/main/resources/data/bingo/objectives/action/enter_end.json` | N4 | **N5** |
| Entrer dans le Nether (`bingo:action/enter_nether`) | `src/main/resources/data/bingo/objectives/action/enter_nether.json` | N3 | **N4** |
| Libérer l'End (`bingo:action/kill_dragon`) | `src/main/resources/data/bingo/objectives/action/kill_dragon.json` | N4 | **N5** |
| Fabriquer un alambic (`bingo:craft/brewing_stand`) | `src/main/resources/data/bingo/objectives/craft/brewing_stand.json` | N3 | **N4** |
| Fabriquer une pioche en netherite (`bingo:craft/netherite_pickaxe`) | `src/main/resources/data/bingo/objectives/craft/netherite_pickaxe.json` | N4 | **N5** |
| Mourir sur un cactus (`bingo:death/cactus`) | `src/main/resources/data/bingo/objectives/death/cactus.json` | N1 | **N2** |
| Mourir noyé (`bingo:death/drown`) | `src/main/resources/data/bingo/objectives/death/drown.json` | N2 | **N1** |
| Mourir dans une explosion (`bingo:death/explosion`) | `src/main/resources/data/bingo/objectives/death/explosion.json` | N2 | **N1** |
| Mourir dans la lave (`bingo:death/lava`) | `src/main/resources/data/bingo/objectives/death/lava.json` | N3 | **N1** |
| Mourir de l'effet Wither (`bingo:death/wither_effect`) | `src/main/resources/data/bingo/objectives/death/wither_effect.json` | N3 | **N5** |
| Obtenir une tige de blaze (`bingo:find/blaze_rod`) | `src/main/resources/data/bingo/objectives/find/blaze_rod.json` | N3 | **N4** |
| Obtenir une perle de l'Ender (`bingo:find/ender_pearl`) | `src/main/resources/data/bingo/objectives/find/ender_pearl.json` | N3 | **N2** |
| Obtenir une étoile du Nether (`bingo:find/nether_star`) | `src/main/resources/data/bingo/objectives/find/nether_star.json` | N4 | **N5** |
| Tuer un blaze (`bingo:kill/blaze`) | `src/main/resources/data/bingo/objectives/kill/blaze.json` | N3 | **N4** |
| Tuer un creeper (`bingo:kill/creeper`) | `src/main/resources/data/bingo/objectives/kill/creeper.json` | N2 | **N1** |
| Tuer un guardien ancien (`bingo:kill/elder_guardian`) | `src/main/resources/data/bingo/objectives/kill/elder_guardian.json` | N4 | **N5** |
| Tuer 2 Endermen (`bingo:kill/enderman`) | `src/main/resources/data/bingo/objectives/kill/enderman.json` | N3 | **N2** |
| Tuer 3 squelettes (`bingo:kill/skeleton`) | `src/main/resources/data/bingo/objectives/kill/skeleton.json` | N2 | **N1** |
| Tuer le Wither (`bingo:kill/wither`) | `src/main/resources/data/bingo/objectives/kill/wither.json` | N4 | **N5** |

## 2. Autres champs

| Objectif | Champ | Actuel | Nouveau |
|---|---|---|---|
| `bingo:death/lava` | `weight` | 6 | **7** |

> Rappel : si `count` change, `display.icon_count` doit suivre, et le texte `bingo.obj.*` qui annonce la quantité aussi (fr_fr et en_us).

## 4. Nouveaux objectifs à créer

### `bingo:craft/smooth_stone` — niveau 1

Fichier à créer : `src/main/resources/data/bingo/objectives/craft/smooth_stone.json`

```json
{
  "type": "bingo:craft",
  "level": 1,
  "target": {
    "item": "minecraft:smooth_stone"
  },
  "display": {
    "icon": "minecraft:smooth_stone",
    "name": {
      "translate": "bingo.obj.craft.smooth_stone"
    },
    "description": {
      "translate": "bingo.obj.craft.smooth_stone.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.craft.smooth_stone": "Craft 1 Pierre Lisse",
"bingo.obj.craft.smooth_stone.desc": "Cuire 2 fois de la cobblestone"
// assets/bingo/lang/en_us.json
"bingo.obj.craft.smooth_stone": "Craft 1 Smooth Stone",
"bingo.obj.craft.smooth_stone.desc": "Cook twice cobblestone"
```

### `bingo:find/wool` — niveau 1

Fichier à créer : `src/main/resources/data/bingo/objectives/find/wool.json`

```json
{
  "type": "bingo:find",
  "level": 1,
  "target": {
    "item": "minecraft:white_wool"
  },
  "count": 2,
  "display": {
    "icon": "minecraft:white_wool",
    "name": {
      "translate": "bingo.obj.find.wool"
    },
    "description": {
      "translate": "bingo.obj.find.wool.desc"
    },
    "icon_count": 2
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.find.wool": "Obtenir 2 Laine Blanche",
"bingo.obj.find.wool.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.find.wool": "Obtain 2 White wool",
"bingo.obj.find.wool.desc": ""
```

### `bingo:kill/pig` — niveau 1

Fichier à créer : `src/main/resources/data/bingo/objectives/kill/pig.json`

```json
{
  "type": "bingo:kill_mob",
  "level": 1,
  "target": {
    "entity_type": "minecraft:pig_spawn_egg"
  },
  "display": {
    "icon": "minecraft:pig_spawn_egg",
    "name": {
      "translate": "bingo.obj.kill.pig"
    },
    "description": {
      "translate": "bingo.obj.kill.pig.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.kill.pig": "Tuer une Cochon",
"bingo.obj.kill.pig.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.kill.pig": "Kill a Pig",
"bingo.obj.kill.pig.desc": ""
```

### `bingo:kill/3endermen` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/kill/3endermen.json`

```json
{
  "type": "bingo:kill_mob",
  "level": 3,
  "target": {
    "entity_type": "minecraft:enderman_spawn_egg"
  },
  "count": 3,
  "display": {
    "icon": "minecraft:enderman_spawn_egg",
    "name": {
      "translate": "bingo.obj.kill.3endermen"
    },
    "description": {
      "translate": "bingo.obj.kill.3endermen.desc"
    },
    "icon_count": 3
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.kill.3endermen": "Tuer 4 Endermen",
"bingo.obj.kill.3endermen.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.kill.3endermen": "Kill 4 Endermen",
"bingo.obj.kill.3endermen.desc": ""
```

### `bingo:craft/jukebox` — niveau 2

Fichier à créer : `src/main/resources/data/bingo/objectives/craft/jukebox.json`

```json
{
  "type": "bingo:craft",
  "level": 2,
  "target": {
    "item": "minecraft:jukebox"
  },
  "display": {
    "icon": "minecraft:jukebox",
    "name": {
      "translate": "bingo.obj.craft.jukebox"
    },
    "description": {
      "translate": "bingo.obj.craft.jukebox.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.craft.jukebox": "Craft un Jukebox",
"bingo.obj.craft.jukebox.desc": "Besoin d'un diamant"
// assets/bingo/lang/en_us.json
"bingo.obj.craft.jukebox": "Craft a Jukebox",
"bingo.obj.craft.jukebox.desc": "Need a Diamond"
```

### `bingo:death/anvil` — niveau 4

Fichier à créer : `src/main/resources/data/bingo/objectives/death/anvil.json`

```json
{
  "type": "bingo:death",
  "level": 4,
  "target": {
    "damage_type": "minecraft:anvil"
  },
  "display": {
    "icon": "minecraft:anvil",
    "name": {
      "translate": "bingo.obj.death.anvil"
    },
    "description": {
      "translate": "bingo.obj.death.anvil.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.death.anvil": "Mourir écrasé par une enclume",
"bingo.obj.death.anvil.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.death.anvil": "Die squashed by an anvil",
"bingo.obj.death.anvil.desc": ""
```

### `bingo:craft/candle` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/craft/candle.json`

```json
{
  "type": "bingo:craft",
  "level": 3,
  "target": {
    "item": "minecraft:candle"
  },
  "display": {
    "icon": "minecraft:candle",
    "name": {
      "translate": "bingo.obj.craft.candle"
    },
    "description": {
      "translate": "bingo.obj.craft.candle.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.craft.candle": "Craft une Bougie",
"bingo.obj.craft.candle.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.craft.candle": "Craft a Candle",
"bingo.obj.craft.candle.desc": ""
```

### `bingo:death/arrow` — niveau 1

Fichier à créer : `src/main/resources/data/bingo/objectives/death/arrow.json`

```json
{
  "type": "bingo:death",
  "level": 1,
  "target": {
    "damage_type": "minecraft:arrow"
  },
  "display": {
    "icon": "minecraft:arrow",
    "name": {
      "translate": "bingo.obj.death.arrow"
    },
    "description": {
      "translate": "bingo.obj.death.arrow.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.death.arrow": "Mourir d'une flèche",
"bingo.obj.death.arrow.desc": "D'un squelette, d'un autre joueur, peut importe"
// assets/bingo/lang/en_us.json
"bingo.obj.death.arrow": "Die from an arrow",
"bingo.obj.death.arrow.desc": "From a skeleton, another player, anything"
```

### `bingo:craft/cake` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/craft/cake.json`

```json
{
  "type": "bingo:craft",
  "level": 3,
  "target": {
    "item": "minecraft:cake"
  },
  "display": {
    "icon": "minecraft:cake",
    "name": {
      "translate": "bingo.obj.craft.cake"
    },
    "description": {
      "translate": "bingo.obj.craft.cake.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.craft.cake": "Craft un Gateau",
"bingo.obj.craft.cake.desc": "Lait, Oeufs, Sucre, Blé"
// assets/bingo/lang/en_us.json
"bingo.obj.craft.cake": "Craft a Cake",
"bingo.obj.craft.cake.desc": "Milk, Eggs, Sugar, Wheat"
```

### `bingo:find/milkbucket` — niveau 2

Fichier à créer : `src/main/resources/data/bingo/objectives/find/milkbucket.json`

```json
{
  "type": "bingo:find",
  "level": 2,
  "target": {
    "item": "minecraft:milk_bucket"
  },
  "display": {
    "icon": "minecraft:milk_bucket",
    "name": {
      "translate": "bingo.obj.find.milkbucket"
    },
    "description": {
      "translate": "bingo.obj.find.milkbucket.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.find.milkbucket": "Obtenir un Seau de Lait",
"bingo.obj.find.milkbucket.desc": "De vache"
// assets/bingo/lang/en_us.json
"bingo.obj.find.milkbucket": "Obtain a Milk Bucket",
"bingo.obj.find.milkbucket.desc": "Cow milk"
```

### `bingo:craft/suspiciousstew` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/craft/suspiciousstew.json`

```json
{
  "type": "bingo:craft",
  "level": 3,
  "target": {
    "item": "minecraft:suspicious_stew"
  },
  "display": {
    "icon": "minecraft:suspicious_stew",
    "name": {
      "translate": "bingo.obj.craft.suspiciousstew"
    },
    "description": {
      "translate": "bingo.obj.craft.suspiciousstew.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.craft.suspiciousstew": "Craft une Soupe Suspecte",
"bingo.obj.craft.suspiciousstew.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.craft.suspiciousstew": "Craft a Supicious Stew",
"bingo.obj.craft.suspiciousstew.desc": ""
```

### `bingo:find/musicdisk` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/find/musicdisk.json`

```json
{
  "type": "bingo:find",
  "level": 3,
  "target": {
    "item": "minecraft:music_disk"
  },
  "display": {
    "icon": "minecraft:music_disk_chirp",
    "name": {
      "translate": "bingo.obj.find.musicdisk"
    },
    "description": {
      "translate": "bingo.obj.find.musicdisk.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.find.musicdisk": "Obtenir un Disque",
"bingo.obj.find.musicdisk.desc": "Un Squelette qui tue un Creeper"
// assets/bingo/lang/en_us.json
"bingo.obj.find.musicdisk": "Obtain a Music Disk",
"bingo.obj.find.musicdisk.desc": "A Skeleton killing a Creeper"
```

**Note** : n'importe quel disque de musique

### `bingo:find/axolotlbucket` — niveau 3

Fichier à créer : `src/main/resources/data/bingo/objectives/find/axolotlbucket.json`

```json
{
  "type": "bingo:find",
  "level": 3,
  "target": {
    "item": "minecraft:axolotl_bucket"
  },
  "weight": 8,
  "display": {
    "icon": "minecraft:axolotl_bucket",
    "name": {
      "translate": "bingo.obj.find.axolotlbucket"
    },
    "description": {
      "translate": "bingo.obj.find.axolotlbucket.desc"
    }
  }
}
```

Clés de langue à ajouter :

```json
// assets/bingo/lang/fr_fr.json
"bingo.obj.find.axolotlbucket": "Obtenir un Axolot dans un Seau",
"bingo.obj.find.axolotlbucket.desc": ""
// assets/bingo/lang/en_us.json
"bingo.obj.find.axolotlbucket": "Obtain an Axolotl in a Bucket",
"bingo.obj.find.axolotlbucket.desc": ""
```

---

<details><summary>Change set brut (à recoller dans l'outil pour reprendre l'édition)</summary>

```json
{
  "schema": "bingo-objective-editor/1",
  "snapshot": "2026-07-30 13:32",
  "level_changes": [
    {
      "id": "bingo:action/enchant_item",
      "file": "src/main/resources/data/bingo/objectives/action/enchant_item.json",
      "name": "Enchanter un objet",
      "from": 3,
      "to": 4
    },
    {
      "id": "bingo:action/enter_end",
      "file": "src/main/resources/data/bingo/objectives/action/enter_end.json",
      "name": "Entrer dans l'End",
      "from": 4,
      "to": 5
    },
    {
      "id": "bingo:action/enter_nether",
      "file": "src/main/resources/data/bingo/objectives/action/enter_nether.json",
      "name": "Entrer dans le Nether",
      "from": 3,
      "to": 4
    },
    {
      "id": "bingo:action/kill_dragon",
      "file": "src/main/resources/data/bingo/objectives/action/kill_dragon.json",
      "name": "Libérer l'End",
      "from": 4,
      "to": 5
    },
    {
      "id": "bingo:craft/brewing_stand",
      "file": "src/main/resources/data/bingo/objectives/craft/brewing_stand.json",
      "name": "Fabriquer un alambic",
      "from": 3,
      "to": 4
    },
    {
      "id": "bingo:craft/netherite_pickaxe",
      "file": "src/main/resources/data/bingo/objectives/craft/netherite_pickaxe.json",
      "name": "Fabriquer une pioche en netherite",
      "from": 4,
      "to": 5
    },
    {
      "id": "bingo:death/cactus",
      "file": "src/main/resources/data/bingo/objectives/death/cactus.json",
      "name": "Mourir sur un cactus",
      "from": 1,
      "to": 2
    },
    {
      "id": "bingo:death/drown",
      "file": "src/main/resources/data/bingo/objectives/death/drown.json",
      "name": "Mourir noyé",
      "from": 2,
      "to": 1
    },
    {
      "id": "bingo:death/explosion",
      "file": "src/main/resources/data/bingo/objectives/death/explosion.json",
      "name": "Mourir dans une explosion",
      "from": 2,
      "to": 1
    },
    {
      "id": "bingo:death/lava",
      "file": "src/main/resources/data/bingo/objectives/death/lava.json",
      "name": "Mourir dans la lave",
      "from": 3,
      "to": 1
    },
    {
      "id": "bingo:death/wither_effect",
      "file": "src/main/resources/data/bingo/objectives/death/wither_effect.json",
      "name": "Mourir de l'effet Wither",
      "from": 3,
      "to": 5
    },
    {
      "id": "bingo:find/blaze_rod",
      "file": "src/main/resources/data/bingo/objectives/find/blaze_rod.json",
      "name": "Obtenir une tige de blaze",
      "from": 3,
      "to": 4
    },
    {
      "id": "bingo:find/ender_pearl",
      "file": "src/main/resources/data/bingo/objectives/find/ender_pearl.json",
      "name": "Obtenir une perle de l'Ender",
      "from": 3,
      "to": 2
    },
    {
      "id": "bingo:find/nether_star",
      "file": "src/main/resources/data/bingo/objectives/find/nether_star.json",
      "name": "Obtenir une étoile du Nether",
      "from": 4,
      "to": 5
    },
    {
      "id": "bingo:kill/blaze",
      "file": "src/main/resources/data/bingo/objectives/kill/blaze.json",
      "name": "Tuer un blaze",
      "from": 3,
      "to": 4
    },
    {
      "id": "bingo:kill/creeper",
      "file": "src/main/resources/data/bingo/objectives/kill/creeper.json",
      "name": "Tuer un creeper",
      "from": 2,
      "to": 1
    },
    {
      "id": "bingo:kill/elder_guardian",
      "file": "src/main/resources/data/bingo/objectives/kill/elder_guardian.json",
      "name": "Tuer un guardien ancien",
      "from": 4,
      "to": 5
    },
    {
      "id": "bingo:kill/enderman",
      "file": "src/main/resources/data/bingo/objectives/kill/enderman.json",
      "name": "Tuer 2 Endermen",
      "from": 3,
      "to": 2
    },
    {
      "id": "bingo:kill/skeleton",
      "file": "src/main/resources/data/bingo/objectives/kill/skeleton.json",
      "name": "Tuer 3 squelettes",
      "from": 2,
      "to": 1
    },
    {
      "id": "bingo:kill/wither",
      "file": "src/main/resources/data/bingo/objectives/kill/wither.json",
      "name": "Tuer le Wither",
      "from": 4,
      "to": 5
    }
  ],
  "field_changes": [
    {
      "id": "bingo:death/lava",
      "file": "src/main/resources/data/bingo/objectives/death/lava.json",
      "field": "weight",
      "from": 6,
      "to": 7
    }
  ],
  "notes": [],
  "new_objectives": [
    {
      "key": "new-2",
      "path": "craft/smooth_stone",
      "id": "bingo:craft/smooth_stone",
      "file": "src/main/resources/data/bingo/objectives/craft/smooth_stone.json",
      "type": "bingo:craft",
      "level": 1,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:smooth_stone",
      "target": {
        "item": "minecraft:smooth_stone"
      },
      "tags": [],
      "name_fr": "Craft 1 Pierre Lisse",
      "name_en": "Craft 1 Smooth Stone",
      "desc_fr": "Cuire 2 fois de la cobblestone",
      "desc_en": "Cook twice cobblestone",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-3",
      "path": "find/wool",
      "id": "bingo:find/wool",
      "file": "src/main/resources/data/bingo/objectives/find/wool.json",
      "type": "bingo:find",
      "level": 1,
      "weight": 10,
      "count": 2,
      "icon": "minecraft:white_wool",
      "target": {
        "item": "minecraft:white_wool"
      },
      "tags": [],
      "name_fr": "Obtenir 2 Laine Blanche",
      "name_en": "Obtain 2 White wool",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-4",
      "path": "kill/pig",
      "id": "bingo:kill/pig",
      "file": "src/main/resources/data/bingo/objectives/kill/pig.json",
      "type": "bingo:kill_mob",
      "level": 1,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:pig_spawn_egg",
      "target": {
        "entity_type": "minecraft:pig_spawn_egg"
      },
      "tags": [],
      "name_fr": "Tuer une Cochon",
      "name_en": "Kill a Pig",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-5",
      "path": "kill/3endermen",
      "id": "bingo:kill/3endermen",
      "file": "src/main/resources/data/bingo/objectives/kill/3endermen.json",
      "type": "bingo:kill_mob",
      "level": 3,
      "weight": 10,
      "count": 3,
      "icon": "minecraft:enderman_spawn_egg",
      "target": {
        "entity_type": "minecraft:enderman_spawn_egg"
      },
      "tags": [],
      "name_fr": "Tuer 4 Endermen",
      "name_en": "Kill 4 Endermen",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-6",
      "path": "craft/jukebox",
      "id": "bingo:craft/jukebox",
      "file": "src/main/resources/data/bingo/objectives/craft/jukebox.json",
      "type": "bingo:craft",
      "level": 2,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:jukebox",
      "target": {
        "item": "minecraft:jukebox"
      },
      "tags": [],
      "name_fr": "Craft un Jukebox",
      "name_en": "Craft a Jukebox",
      "desc_fr": "Besoin d'un diamant",
      "desc_en": "Need a Diamond",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-7",
      "path": "death/anvil",
      "id": "bingo:death/anvil",
      "file": "src/main/resources/data/bingo/objectives/death/anvil.json",
      "type": "bingo:death",
      "level": 4,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:anvil",
      "target": {
        "damage_type": "minecraft:anvil"
      },
      "tags": [],
      "name_fr": "Mourir écrasé par une enclume",
      "name_en": "Die squashed by an anvil",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-8",
      "path": "craft/candle",
      "id": "bingo:craft/candle",
      "file": "src/main/resources/data/bingo/objectives/craft/candle.json",
      "type": "bingo:craft",
      "level": 3,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:candle",
      "target": {
        "item": "minecraft:candle"
      },
      "tags": [],
      "name_fr": "Craft une Bougie",
      "name_en": "Craft a Candle",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-9",
      "path": "death/arrow",
      "id": "bingo:death/arrow",
      "file": "src/main/resources/data/bingo/objectives/death/arrow.json",
      "type": "bingo:death",
      "level": 1,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:arrow",
      "target": {
        "damage_type": "minecraft:arrow"
      },
      "tags": [],
      "name_fr": "Mourir d'une flèche",
      "name_en": "Die from an arrow",
      "desc_fr": "D'un squelette, d'un autre joueur, peut importe",
      "desc_en": "From a skeleton, another player, anything",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-10",
      "path": "craft/cake",
      "id": "bingo:craft/cake",
      "file": "src/main/resources/data/bingo/objectives/craft/cake.json",
      "type": "bingo:craft",
      "level": 3,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:cake",
      "target": {
        "item": "minecraft:cake"
      },
      "tags": [],
      "name_fr": "Craft un Gateau",
      "name_en": "Craft a Cake",
      "desc_fr": "Lait, Oeufs, Sucre, Blé",
      "desc_en": "Milk, Eggs, Sugar, Wheat",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-11",
      "path": "find/milkbucket",
      "id": "bingo:find/milkbucket",
      "file": "src/main/resources/data/bingo/objectives/find/milkbucket.json",
      "type": "bingo:find",
      "level": 2,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:milk_bucket",
      "target": {
        "item": "minecraft:milk_bucket"
      },
      "tags": [],
      "name_fr": "Obtenir un Seau de Lait",
      "name_en": "Obtain a Milk Bucket",
      "desc_fr": "De vache",
      "desc_en": "Cow milk",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-12",
      "path": "craft/suspiciousstew",
      "id": "bingo:craft/suspiciousstew",
      "file": "src/main/resources/data/bingo/objectives/craft/suspiciousstew.json",
      "type": "bingo:craft",
      "level": 3,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:suspicious_stew",
      "target": {
        "item": "minecraft:suspicious_stew"
      },
      "tags": [],
      "name_fr": "Craft une Soupe Suspecte",
      "name_en": "Craft a Supicious Stew",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    },
    {
      "key": "new-13",
      "path": "find/musicdisk",
      "id": "bingo:find/musicdisk",
      "file": "src/main/resources/data/bingo/objectives/find/musicdisk.json",
      "type": "bingo:find",
      "level": 3,
      "weight": 10,
      "count": 1,
      "icon": "minecraft:music_disk_chirp",
      "target": {
        "item": "minecraft:music_disk"
      },
      "tags": [],
      "name_fr": "Obtenir un Disque",
      "name_en": "Obtain a Music Disk",
      "desc_fr": "Un Squelette qui tue un Creeper",
      "desc_en": "A Skeleton killing a Creeper",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": "n'importe quel disque de musique"
    },
    {
      "key": "new-14",
      "path": "find/axolotlbucket",
      "id": "bingo:find/axolotlbucket",
      "file": "src/main/resources/data/bingo/objectives/find/axolotlbucket.json",
      "type": "bingo:find",
      "level": 3,
      "weight": 8,
      "count": 1,
      "icon": "minecraft:axolotl_bucket",
      "target": {
        "item": "minecraft:axolotl_bucket"
      },
      "tags": [],
      "name_fr": "Obtenir un Axolot dans un Seau",
      "name_en": "Obtain an Axolotl in a Bucket",
      "desc_fr": "",
      "desc_en": "",
      "requires_dimension": "",
      "conflicts": [],
      "points_base": null,
      "announce": true,
      "note": ""
    }
  ],
  "profile_changes": [],
  "deletions": []
}
```

</details>