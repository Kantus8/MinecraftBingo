---
name: minecraft-bingo
description: Carte d'architecture du mod Minecraft Bingo (Fabric 1.20.1, dépôt MinecraftBingo) — où vit quoi, quels invariants tenir, quels fichiers toucher pour chaque type de tâche, et comment lire ce dépôt sans en avaler le contexte. À charger dès qu'une tâche concerne ce mod, même sans qu'on le nomme : ajout ou correction d'un objectif, d'une case, d'une équipe, d'un paquet réseau, d'un élément de HUD, d'une commande /bingo, du scoring, du vocal, de JEI, de l'animation de tirage, de la persistance NBT, d'un datapack, ou simplement « pourquoi le HUD ne s'affiche pas ». Utile aussi pour toute question d'orientation (« où est géré X ? », « comment marche Y ici ? ») avant d'ouvrir le moindre fichier.
---

# Minecraft Bingo — carte du dépôt

Mod Fabric 1.20.1 : bingo 5×5 en équipes, HUD permanent, animation de tirage, intégration Simple
Voice Chat et JEI. Objectifs et règles définis en datapack.

## Pourquoi lire ce skill avant le code

Le dépôt est documenté à l'excès — javadoc française qui explique le *pourquoi* de chaque décision,
souvent 3 lignes de commentaire par ligne de code. C'est précieux à écrire, ruineux à parcourir :
`BingoGame.java` seul fait ~1100 lignes. Ouvrir cinq fichiers « pour voir » coûte 40 000 tokens et
répond à une question que la table de routage ci-dessous résout en zéro.

**La discipline qui économise le plus : lire des fenêtres, pas des fichiers.**

## Protocole de lecture

Les cinq gros fichiers, à ne jamais ouvrir en entier sans raison :

| Fichier | ~lignes | Ce qu'il contient |
|---|---|---|
| `src/main/java/com/bingo/mod/game/BingoGame.java` | 1100 | état de partie, machine à phases, validation, persistance |
| `src/main/java/com/bingo/mod/command/BingoCommand.java` | 1050 | tout l'arbre `/bingo` |
| `src/client/java/com/bingo/mod/client/BingoClientState.java` | 500 | miroir client, visibilité du HUD |
| `src/client/java/com/bingo/mod/client/hud/BingoBoardRenderer.java` | 410 | dessin de la grille |
| `src/main/java/com/bingo/mod/config/BingoServerConfig.java` | 410 | clés de config serveur |

Ces fichiers sont découpés par des bandeaux `// ── Titre ───`. C'est un sommaire greppable :

```bash
grep -n "// ── " src/main/java/com/bingo/mod/game/BingoGame.java
```

Puis `Read` avec `offset`/`limit` sur la seule section utile. Pour trouver un symbole précis,
`grep -n "nomDeMethode"` bat toujours la lecture séquentielle.

## Routage : la tâche → le fichier

| La tâche parle de… | Aller directement à |
|---|---|
| une case validée, un objectif détecté | `game/detect/ObjectiveValidator.java`, puis `BingoGame.applyProgress` |
| le score d'une équipe, le classement, les égalités | `game/BingoScoring.java` (117 l., lisible en entier) |
| les points individuels cumulés | `game/PlayerPoints.java` |
| une combinaison gagnante, le masque de 25 bits | `board/WinLines.java` |
| le tirage de la carte | `board/BoardGenerator.java`, `data/PoolResolver.java` |
| les équipes, l'appartenance, l'autobalance | `game/team/TeamManager.java`, `game/team/BingoTeam.java` |
| une phase, une transition | `game/phase/GamePhase.java`, puis section « Machine à états » de `BingoGame` |
| un paquet, une désync | `references/network.md` |
| le HUD, un panneau, une texture, un keybind | `references/hud-client.md` |
| une commande `/bingo` | bandeaux de `BingoCommand.java` (`/bingo team`, `/bingo points`, `/bingo config`, `/bingo debug`…) |
| un objectif JSON, un pool, un ruleset, une difficulté | `references/datapack.md` |
| le vocal | `integration/voicechat/BingoVoiceManager.java` + `docs/02` |
| l'animation de tirage | `client/roll/RollAnimationState.java` + `docs/04` |
| la sauvegarde du monde, le NBT | sections « Persistance » de `BingoGame`, `BingoTeam`, `PlayerPoints`, et `world/BingoPersistentState.java` |
| un événement vanilla à intercepter | `game/detect/BingoDetectors.java` (events Fabric) ou `mixin/` (5 mixins serveur) |

## Le projet en dix lignes

- **Stack** : Minecraft 1.20.1, Fabric Loader 0.16.14, yarn `1.20.1+build.10`, Java 17.
  Toutes les versions vivent dans `gradle.properties` — jamais dans `build.gradle`.
- **Deux source sets**, et la frontière compte (`docs/06` §5) :
  - `src/main/java` — logique serveur **et** tout ce qui est partagé : records de paquets
    (`network/payload/`), identifiants de canaux, constantes, modèle de données.
  - `src/client/java` — rendu, écrans, config client, keybinds. Jamais de décision de jeu.
- **Entrypoints** (`fabric.mod.json`) : `BingoMod` (main), `BingoModClient` (client),
  `BingoModServer` (server), `BingoDataGenerator` (datagen), plus les plugins voicechat et JEI.
- **Mixins** : `bingo.mixins.json` (5 mixins serveur : craft, avancement, apprivoisement, troc,
  enchantement) et `bingo.client.mixins.json`.
- **Datapack livré** : `src/main/resources/data/bingo/` — 103 objectifs, 1 pool, 5 difficultés,
  1 ruleset.

## Invariants — les tenir, ne pas les redécouvrir

Ces règles expliquent la forme du code. Les enfreindre compile parfaitement et casse le jeu.

1. **Rien n'est stocké deux fois.** Score, nombre de cases, victoire, temps écoulé : tout se dérive
   de `completionMask` et de `startedAtMs`, recalculé à chaque appel. Un état qui ne peut pas devenir
   incohérent est un état minimal. **Seule exception assumée** : `PlayerPoints`, cumul individuel qui
   traverse les manches — il n'a rien dont se dériver, et c'est documenté comme tel.
2. **Un seul chemin coche une case** : `BingoGame.applyProgress`. Victoire, score, paquets et annonces
   en découlent. Un `team.complete()` appelé ailleurs produirait une case validée sans détection de
   victoire — le bug se verrait trois manches plus tard.
3. **Le client ne décide rien** (`docs/06` §3.4). `BingoClientState` est purement présentationnel :
   aucune validation d'objectif, jamais. Il ne connaît même pas `points_base` par objectif.
4. **Le layout a une source unique.** `BingoBoardLayout` et `BingoTeamPanelLayout` sont lus par
   l'overlay HUD *et* par les écrans. C'est ce qui fait tenir l'illusion « le HUD devient cliquable ».
   Recalculer une position ailleurs la désaligne d'un pixel le jour où le réglage d'échelle change.
5. **Décoder le `PacketByteBuf` dans le handler réseau**, puis passer les objets décodés à
   `client.execute(...)` / `server.execute(...)`. Le buffer est libéré au retour du handler ; y toucher
   depuis le thread principal donne une corruption intermittente.
6. **Aucun paquet par tick** (`docs/06` §4). Les paquets partent sur des transitions et des
   validations. Un test « la valeur a-t-elle changé ? » précède chaque émission.
7. **Deux fichiers de lang, toujours** : `en_us.json` et `fr_fr.json`. Une clé ajoutée d'un seul côté
   s'affiche en brut chez la moitié des joueurs.

## Références — charger seulement la bonne

| Fichier | Le charger quand la tâche touche |
|---|---|
| `references/network.md` | un paquet, un canal, la synchronisation, une désync, l'ordre d'envoi |
| `references/hud-client.md` | le HUD, un panneau, un écran, une texture, la config client, un keybind |
| `references/gameplay-server.md` | phases, équipes, scoring, points, détection, spectateurs, persistance |
| `references/datapack.md` | un JSON de `data/bingo/`, un codec, un loader, la résolution des réglages |

## Références vers `docs/` — huit specs, une seule à ouvrir

Le javadoc cite ces documents en permanence (`docs/05` §2.1…). Ce sont les specs d'origine : elles
disent l'intention, le code dit l'état réel. **En cas de contradiction, le code a raison** — et
plusieurs écarts sont assumés et documentés à l'endroit de l'écart.

| Doc | Répond à |
|---|---|
| `docs/00-OVERVIEW.md` | piliers de design, glossaire, décisions actées |
| `docs/01-DATAPACK-SCHEMA.md` | schéma des objectifs, 5 types, 5 niveaux, pools, difficultés, ruleset |
| `docs/02-VOICECHAT-SPEC.md` | machine à états vocale, groupes `OPEN`, cas limites |
| `docs/03-HUD-JEI-SPEC.md` | valeurs pixel du layout, états visuels d'une case, routage du clic, keybinds |
| `docs/04-SLOT-MACHINE-VFX.md` | timeline du tirage, déterminisme par seed, gel des joueurs |
| `docs/05-RULES-SCORING-COMMANDS.md` | victoire, barème, composition des équipes, arbre des commandes, annonces |
| `docs/06-NETWORK-AND-STATE.md` | paquets, machine à états, modèle de données, répartition des source sets |
| `docs/07-IMPLEMENTATION-PLAN.md` | historique des lots — utile pour comprendre un « lot 4 » cité en commentaire |

## Recettes

**Ajouter un paquet S2C** — 5 sites, aucun facultatif : voir `references/network.md`.

**Ajouter une clé de traduction** — `BingoConstants.key("mon.suffixe")` produit `bingo.mon.suffixe` ;
la déclarer dans `en_us.json` **et** `fr_fr.json`.

**Ajouter un réglage** — serveur exposé à `/bingo config` → `BingoServerConfig` (le tableau de
`Setting` porte bornes et suggestions) ; réglage de fenêtre propre à un joueur → `BingoClientConfig`
(`config/bingo-client.json`, classe `Values` à champs publics pour que Gson tolère un fichier partiel).

**Ajouter une sous-commande** — un `LiteralArgumentBuilder` par sous-arbre, branché dans `register()`.
Erreurs : `SimpleCommandExceptionType`/`DynamicCommandExceptionType` portant une clé de traduction —
jamais `sendError` + `return 0`, qui laisse croire au reste du code que la commande a tourné.
Toute mutation d'équipe passe par `afterTeamChange(game)` : persistance, `team_sync`, vocal.

**Toucher au datapack livré** — les JSON de `src/main/resources/data/bingo/` ; `/bingo reload` les
relit à chaud, et `BingoGame.onDataReload()` réaligne la carte en cours.

## Vérifier

```bash
./gradlew compileJava compileClientJava --offline
```

```bash
./gradlew build --offline
```

Pas de tests, pas de checkstyle : la compilation des deux source sets est le seul filet. `--offline`
évite d'attendre les dépôts Maven quand rien n'a changé côté dépendances. `runDatagen` régénère
`src/main/generated` — le churn dans `.cache/` y est normal et se committe avec le reste.

Pour voir tourner le mod : `./gradlew runClient`, puis `/bingo debug solo` monte une manche jouable à
un joueur (deux équipes, préconditions levées) — la seule façon de tester la boucle sans quatre
comptes.

## Style — le contrat d'écriture

Le dépôt a une voix. S'en écarter produit un patch qui se voit immédiatement.

- **Indentation par tabulations**, comme le code existant.
- **Javadoc et commentaires en français**, et ils expliquent le **pourquoi**, jamais le quoi. Un
  commentaire qui paraphrase la ligne suivante est du bruit ; un commentaire qui dit « décalage de
  bits et non `Math.pow`, parce qu'un score entier doit rester entier » est ce qu'on attend ici.
- **Nommer l'alternative écartée** quand un choix surprend. C'est le motif dominant du dépôt : le
  lecteur suivant ne se demande pas si on a oublié l'évidence.
- **Bandeaux `// ── Titre ───`** pour découper un fichier qui dépasse ~200 lignes.
- Les écarts avec `docs/` sont **documentés à l'endroit de l'écart**, avec la raison.
