# Minecraft Bingo

Mod **Fabric 1.20.1** de Bingo compétitif multijoueur : cartes d'objectifs générées depuis des datapacks, équipes, HUD temps réel, chat vocal de proximité/équipe via **Simple Voice Chat**, et intégration **JEI**.

> **État du projet : lots 0 à 6 terminés** (le lot 5 sauf les tests unitaires, écartés — voir §6).
> Le mod est jouable de bout en bout : équipes, machine à états des phases, chrono, détection des 5 types d'objectifs, victoire par ligne/colonne/diagonale, HUD 5×5 texturé, écran cliquable (`B`), persistance NBT, arbre `/bingo` complet (dont `/bingo config`), groupes vocaux d'équipe en `OPEN` pilotés par la phase, ouverture de JEI sur la recette d'une case, et l'animation Slot Machine de 3 s avec gel, feux d'artifice et étincelles. Le cadrage complet est dans [`docs/`](docs/00-OVERVIEW.md), le reste du chemin dans [`docs/07`](docs/07-IMPLEMENTATION-PLAN.md).
>
> Un guide séparé pour les joueurs et les organisateurs de partie : [`README-JOUEUR.md`](README-JOUEUR.md).
>
> Deux réserves assumées : aucun `.ogg` custom n'est livré (les sept événements sonores pointent sur des sons vanilla, voir [`docs/04`](docs/04-SLOT-MACHINE-VFX.md) §7), et les textures du HUD sont des sprites géométriques générés, pas de l'illustration.

---

## 1. Stack technique

| Composant | Version | Source |
|---|---|---|
| Minecraft | `1.20.1` | — |
| Java | **17** (obligatoire) | Temurin / MS OpenJDK |
| Gradle | `8.8` (wrapper) | services.gradle.org |
| Fabric Loom | `1.7-SNAPSHOT` | maven.fabricmc.net |
| Yarn mappings | `1.20.1+build.10` | maven.fabricmc.net |
| Fabric Loader | `0.16.14` | maven.fabricmc.net |
| Fabric API | `0.92.9+1.20.1` | maven.fabricmc.net |
| Simple Voice Chat API | `2.5.36` | maven.maxhenkel.de |
| JEI (Fabric API) | `15.21.0.148` | maven.blamejared.com |

Toutes ces versions sont centralisées dans **`gradle.properties`** — c'est le seul fichier à éditer pour monter une version.

### Identité du mod

| Clé | Valeur |
|---|---|
| `mod_id` | `bingo` |
| Nom affiché | Minecraft Bingo |
| Package racine | `com.bingo.mod` |
| Groupe Maven | `com.bingo` |
| Environnement | `*` (client **et** serveur dédié, un seul jar) |

### Dépendances déclarées comme requises

Simple Voice Chat et JEI sont en **`depends`** dans `fabric.mod.json` : le mod refuse de démarrer si l'un des deux est absent. C'est un choix assumé (le vocal d'équipe est un pilier du gameplay). Pour les passer en optionnel plus tard, il suffit de les déplacer de `depends` vers `suggests` et de gérer le chargement conditionnel avec `FabricLoader.getInstance().isModLoaded("voicechat")`.

> ⚠️ Précision sur l'artefact Voice Chat : le coordonnée `de.maxhenkel.voicechat:voicechat-api` est versionnée **indépendamment de Minecraft** (`2.5.36`, et non `1.20.1-2.5.x` qui est la version du *mod* distribué). L'API ne contient aucune classe Minecraft, elle est donc déclarée en `compileOnly` classique — pas en `modCompileOnly`, pas de remap.

---

## 2. Premier démarrage

### Prérequis

- **JDK 17** installé, `java` dans le `PATH` (ou `JAVA_HOME` défini)
- Connexion internet au premier build (téléchargement de Minecraft, des mappings et des dépendances)

### Windows

```bat
setup.bat
```

Le script :
1. télécharge `gradle/wrapper/gradle-wrapper.jar` (non versionné dans le dépôt) ;
2. vérifie la présence d'un JDK ;
3. résout les dépendances pour valider la configuration Gradle.

### Linux / macOS / WSL

```sh
./setup.sh
```

### Ensuite

```bat
gradlew build          :: compile le mod -> build/libs/bingo-0.1.0+mc1.20.1.jar
gradlew runClient      :: lance un client Minecraft de dev avec le mod
gradlew runServer      :: lance un serveur dédié de dev
gradlew runDatagen     :: génère les ressources dans src/main/generated
gradlew genSources     :: décompile Minecraft pour la navigation dans l'IDE
```

### Import dans l'IDE

IntelliJ IDEA : *Open* → sélectionner le dossier du projet → il détecte `build.gradle` seul.
**Vérifier ensuite** `Settings → Build Tools → Gradle → Gradle JVM = 17`. Un Gradle JVM en 21 ou 11 fait échouer Loom sur 1.20.1.

---

## 3. Structure du projet

```
Mod Minecraft Bingo/
├── build.gradle                    Config Loom, dépôts, dépendances
├── gradle.properties               ► TOUTES les versions du projet
├── settings.gradle                 Nom du projet + pluginManagement Fabric
├── gradlew / gradlew.bat           Gradle Wrapper
├── gradle/wrapper/
│   └── gradle-wrapper.properties   Gradle 8.8-bin
├── setup.bat / setup.sh            Bootstrap de l'environnement
├── .gitignore  .editorconfig
├── docs/                           ◄ DOSSIER DE CADRAGE TECHNIQUE (00 → 07)
├── run/                            Environnement de run Minecraft (généré)
│
├── src/main/                       ═══ CODE COMMUN (logique autoritaire) ═══
│   ├── java/com/bingo/mod/
│   │   ├── board/                  Grille, tuiles, état de complétion
│   │   ├── command/                Commandes /bingo
│   │   ├── config/                 Config serveur persistée
│   │   ├── data/
│   │   │   ├── codec/              Codecs DFU des objets datapack
│   │   │   └── loader/             ResourceReloadListener des objectifs
│   │   ├── game/
│   │   │   ├── phase/              Machine à états (lobby/countdown/run/fin)
│   │   │   └── team/               Équipes, appartenance, scores
│   │   ├── integration/
│   │   │   ├── voicechat/          Plugin serveur Simple Voice Chat
│   │   │   └── jei/                Plugin JEI (IModPlugin)
│   │   ├── mixin/                  Mixins communs
│   │   ├── network/
│   │   │   ├── payload/            Paquets C2S / S2C
│   │   │   └── handler/            Handlers serveur
│   │   ├── objective/
│   │   │   ├── type/               Types d'objectifs (craft/find/kill/death/action)
│   │   │   └── condition/          Prédicats de validation
│   │   ├── registry/               Enregistrement des contenus
│   │   ├── util/
│   │   └── world/                  Hooks monde / scoreboard
│   │
│   ├── resources/
│   │   ├── fabric.mod.json         Métadonnées (versions injectées au build)
│   │   ├── bingo.mixins.json       Config mixins communs
│   │   ├── assets/bingo/
│   │   │   ├── icon.png            Icône 128×128
│   │   │   ├── lang/               en_us.json, fr_fr.json
│   │   │   ├── sounds.json         Déclaration des sons
│   │   │   ├── sounds/             ui/ · ambient/ · voice/   (.ogg)
│   │   │   ├── textures/           gui/{board,hud,sprites,icons} · item/ · block/ · font/
│   │   │   ├── models/             item/ · block/
│   │   │   └── blockstates/
│   │   └── data/bingo/
│   │       ├── objectives/         craft/ find/ kill/ death/ action/  (45 livrés)
│   │       ├── pools/              default.json — pool de tirage
│   │       ├── difficulties/       easy · normal · hard · extreme
│   │       ├── rulesets/           classic.json
│   │       ├── cards/              Cartes pré-écrites (parties scriptées)
│   │       ├── tags/               (généré par datagen — voir src/main/generated)
│   │       ├── loot_tables/  recipes/  advancements/  functions/
│   └── generated/                  Sortie de runDatagen — tags/items/roll_decoys.json (leurres)
│
└── src/client/                     ═══ CODE CLIENT UNIQUEMENT ═══
    ├── java/com/bingo/mod/client/
    │   ├── hud/widget/             Overlay HUD
    │   ├── screen/widget/          Écrans (carte, lobby, réglages)
    │   ├── render/                 Rendu monde
    │   ├── input/                  Keybinds
    │   ├── sound/
    │   ├── network/                Réception des paquets S2C
    │   ├── integration/            voicechat/ (ClientVoicechatPlugin) · jei/
    │   ├── mixin/                  Mixins client
    │   └── util/
    └── resources/
        └── bingo.client.mixins.json
```

### Pourquoi deux source sets ?

`splitEnvironmentSourceSets()` est activé dans `build.gradle`. Conséquence : **toute référence à une classe client depuis `src/main` échoue à la compilation**. C'est une garde-fou volontaire — sur un mod multijoueur avec logique serveur autoritaire, c'est ce qui empêche les `NoClassDefFoundError` au lancement d'un serveur dédié.

### Points d'entrée déclarés

| Type | Classe |
|---|---|
| `main` | `com.bingo.mod.BingoMod` |
| `client` | `com.bingo.mod.client.BingoModClient` |
| `server` | `com.bingo.mod.BingoModServer` |
| `fabric-datagen` | `com.bingo.mod.data.BingoDataGenerator` |
| `voicechat` | `com.bingo.mod.integration.voicechat.BingoVoicechatPlugin` |
| `jei_mod_plugin` | `com.bingo.mod.integration.jei.BingoJeiPlugin` |

Les cinq premières classes existent depuis le lot 0 ; `BingoVoicechatPlugin`, stub jusqu'au lot 2, écoute maintenant trois événements et délègue tout à `BingoVoiceManager`.

> ⚠️ **`jei_mod_plugin` n'est pas optionnel sur Fabric.** Contrairement à ce qu'on lit dans la plupart des tutoriels JEI, qui décrivent la version Forge, l'annotation `@JeiPlugin` ne suffit pas ici : `mezz.jei.fabric.startup.FabricPluginFinder` va chercher les plugins dans cet entrypoint. Sans lui, le plugin n'est jamais instancié, `getRuntime()` reste `null` pour toujours, et JEI n'émet **aucun avertissement** — le seul symptôme est un clic gauche qui ne fait rien sur les cases `CRAFT`.

### Mixins

Cinq mixins, tous dans `src/main/java/com/bingo/mod/mixin/` et déclarés dans `bingo.mixins.json`. Ils n'existent que pour les quatre détections qu'aucun événement Fabric ne couvre :

| Mixin | Cible | Détecte |
|---|---|---|
| `ItemStackCraftMixin` | `ItemStack#onCraft` | tous les objectifs `craft` — table, grille 2×2, four, table de forge, table de découpe |
| `EnchantmentScreenHandlerMixin` | `EnchantmentScreenHandler#onButtonClick` | déclencheur `bingo:enchant_item` |
| `VillagerTradeMixin` | `VillagerEntity#afterUsing` | déclencheur `bingo:trade_with_villager` |
| `TameableEntityMixin` | `TameableEntity#setOwner` | déclencheur `bingo:tame_animal` |
| `PlayerAdvancementTrackerMixin` | `PlayerAdvancementTracker#grantCriterion` | déclencheur `bingo:advancement` |

Tout le reste passe par des événements Fabric (`ServerLivingEntityEvents`, `EntitySleepEvents`, `ServerEntityWorldChangeEvents`, `UseBlockCallback`) ou par le tick de partie (scan `FIND` toutes les 10 ticks, altitude toutes les 20).

---

## 4. Dossier de cadrage technique

Le cadrage complet est dans **`docs/`**. Commencer par [`docs/00-OVERVIEW.md`](docs/00-OVERVIEW.md), qui sert d'index et récapitule les décisions actées.

| Doc | Contenu |
|---|---|
[`00-OVERVIEW.md`](docs/00-OVERVIEW.md) | Index, piliers de design, décisions actées, glossaire, pièges de version |
[`01-DATAPACK-SCHEMA.md`](docs/01-DATAPACK-SCHEMA.md) | Schéma JSON des objectifs, 5 types, 4 niveaux, pools, difficultés, rulesets |
[`02-VOICECHAT-SPEC.md`](docs/02-VOICECHAT-SPEC.md) | Machine à états vocale, `Group.Type.OPEN`, API SVC, cas limites |
[`03-HUD-JEI-SPEC.md`](docs/03-HUD-JEI-SPEC.md) | Layout pixel du HUD 5×5, états de case, écran cliquable, routage JEI |
[`04-SLOT-MACHINE-VFX.md`](docs/04-SLOT-MACHINE-VFX.md) | Timeline 3 s, verrouillage par ligne, SFX/VFX, gel des joueurs |
[`05-RULES-SCORING-COMMANDS.md`](docs/05-RULES-SCORING-COMMANDS.md) | 12 combinaisons, formule de score, égalités, arbre des commandes |
[`06-NETWORK-AND-STATE.md`](docs/06-NETWORK-AND-STATE.md) | Phases, modèle de données, paquets, persistance, source sets, passe de durcissement (§7) |
[`07-IMPLEMENTATION-PLAN.md`](docs/07-IMPLEMENTATION-PLAN.md) | Backlog en 5 lots avec critères de recette |

## 5. Contenu de datapack livré

Le cadrage est accompagné de datapacks fonctionnels, conformes au schéma de `docs/01` :

- **58 objectifs** — 5 types (`craft`, `find`, `kill_mob`, `death`, `action`), répartis en 21 N1 / 12 N2 / 9 N3 / 9 N4 / 7 N5 : de quoi remplir les 25 cases des 4 profils sans répétition
- **4 profils de difficulté** — `easy`, `normal`, `hard`, `extreme` (distribution validée à 25 cases)
- **1 pool** (`bingo:default`) et **1 ruleset** (`bingo:classic`)
- **1 tag de leurres** (`#bingo:roll_decoys`, 69 items) pour l'animation de tirage — désormais **généré par datagen** depuis une liste typée (`gradlew runDatagen`)
- Traductions `en_us` et `fr_fr` complètes pour tout ce contenu

---

## 6. Ce qui reste à faire

Le détail ordonné est dans [`docs/07-IMPLEMENTATION-PLAN.md`](docs/07-IMPLEMENTATION-PLAN.md). En résumé :

- [x] **Lot 0** — les 5 classes d'entrypoint (dont un stub `BingoVoicechatPlugin`), `BingoConstants`, `GamePhase`, squelette `BingoGame`, `/bingo status`
- [x] **Lot 1** — records + codecs des 5 types de cible, `ObjectiveLoader` + 6 règles de validation, `Pool` / `DifficultyProfile` / `Ruleset`, résolution de pools, `BoardGenerator` déterministe, `/bingo reload` et `/bingo debug dump`
- [x] **Lot 2** — `BingoTeam` / `TeamManager`, machine à états des 6 phases, chrono, `LINE_MASKS` (12 combinaisons) et score dérivé, index inversés, détecteurs des 5 types + 8 déclencheurs `ACTION`, 8 paquets S2C et `request_sync`, `BingoBoardLayout` / HUD / `BingoBoardScreen`, keybinds `B`, `PersistentState` NBT, arbre `/bingo` complet, annonces et sons
- [x] **Lot 3** — `BingoVoicechatPlugin` (3 événements) et `BingoVoiceManager` (lobby `ISOLATED`, groupes d'équipe `OPEN` cachés, réconciliation à 1 Hz en manche, 9 cas limites), `BingoJeiPlugin` via l'entrypoint `jei_mod_plugin`, `BingoJeiBridge` + repli tooltip sur objectif sans recette
- [x] **Lot 4** — paquet `roll_start` (25 IDs + graine + durée), `RollAnimationState` entièrement dérivé de `elapsed`, défilement à 100 ms puis verrouillage ligne par ligne avec flash et punch, finale (`challenge_complete` + particules `FIREWORK` serveur + étincelles HUD), gel par modificateur d'attribut avec ses deux garde-fous, bordure dorée du 4/5, textures `panel`/`cell`/`check`, sons câblés sur des alias vanilla, config client `bingo-client.json`
- [x] **Lot 5** — datagen du tag `#bingo:roll_decoys` (`BingoItemTagProvider`, liste typée `Items.*` dans `BingoItemTags`), `/bingo config list|get|set` sur les 11 clés serveur avec persistance `bingo-server.json`, passe de perf sur le scan `FIND` (accumulateur `int[]` sans boxing + budget de journalisation), licence **MIT** et champs `contact` remplis, [`README-JOUEUR.md`](README-JOUEUR.md). **Écarté : les tests unitaires (5.2)** — le harnais headless butait sur l'access widener de Fabric API que seul le classloader Knot applique, pour un coût de plomberie de build disproportionné ; à reprendre via `fabric-loader-junit` correctement câblé si le besoin se confirme.

- [x] **Lot 6** — passe de durcissement réseau et état : réalignement de la carte sur un `/bingo reload` en pleine manche (recalage des définitions, ou manche terminée si un objectif a disparu), diffusion du catalogue même sur rechargement en échec, cooldown serveur sur `request_sync`, refus à l'émission d'un paquet au-delà de `1 048 576` octets, plafonds d'allocation sur toutes les collections lues du réseau, borne d'index sur `tile_update`, projections construites une fois par diffusion. Détail des six trous fermés dans [`docs/06` §7](docs/06-NETWORK-AND-STATE.md)

✅ **Tranché le 29 juillet 2026** : les objectifs vers le client passent par un loader de datapack et un paquet `bingo:objective_sync` portant une projection d'affichage — pas de registre dynamique, qui aurait coûté `/bingo reload` (les registres dynamiques ne sont pas rechargés par `/reload` en 1.20.1). Voir [`docs/06` §3.4](docs/06-NETWORK-AND-STATE.md).

---

## 7. Notes de maintenance

**Monter une version** — éditer uniquement `gradle.properties`, puis `gradlew --refresh-dependencies build`.

**JEI et Simple Voice Chat au runtime de dev** — les lignes `modLocalRuntime` de `build.gradle` sont **actives**, et le dépôt Modrinth Maven est déclaré. C'est obligatoire, pas optionnel : les deux mods sont en `depends` dur dans `fabric.mod.json`, donc `runClient` refuse de démarrer sans eux. Ces jars ne sont pas embarqués dans le jar publié.

**`gradle-wrapper.jar` n'est pas versionné** (voir `.gitignore`). Si tu préfères le committer — pratique très répandue et recommandée par Gradle — commente la ligne correspondante dans `.gitignore`. Pour régénérer un wrapper 100 % officiel : `gradlew wrapper --gradle-version 8.8`.
