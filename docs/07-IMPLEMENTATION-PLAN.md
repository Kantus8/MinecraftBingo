# 07 — Plan d'implémentation

Cinq lots de construction (0 à 5), puis une passe de durcissement (lot 6). Chacun est **jouable ou testable à sa fin** — pas de lot qui ne produit que de la plomberie invisible.

---

## Lot 0 — Socle (½ journée)

**But** : le mod se charge, `/bingo status` répond.

| # | Tâche | Fichiers |
|---|---|---|
0.1 | Les **5** classes d'entrypoint, vides mais chargeables | `BingoMod`, `BingoModServer`, `client/BingoModClient`, `data/BingoDataGenerator`, `integration/voicechat/BingoVoicechatPlugin` |
0.2 | Logger + constantes (`MOD_ID`, `id(String)`) | `util/BingoConstants` |
0.3 | Enum `GamePhase` (ordre **contractuel** — cf. `06` §3.3) | `game/phase/GamePhase` |
0.4 | Squelette `BingoGame` singleton attaché au `MinecraftServer` | `game/BingoGame` |
0.5 | `/bingo status` en dur | `command/BingoCommand` |

> ⚠️ **`BingoVoicechatPlugin` appartient au lot 0, pas au lot 3.** L'entrypoint `voicechat` est déjà déclaré dans `fabric.mod.json` et Simple Voice Chat est en `depends` dur : sans cette classe, le jeu ne démarre pas du tout. Un stub qui ne renvoie que `getPluginId()` suffit — la logique de groupes vient au lot 3.

**Recette** : `gradlew runClient` démarre, `/bingo status` affiche `LOBBY`.

> ✅ **Point ouvert n°1 tranché** (29 juillet 2026) : loader de datapack + paquet `bingo:objective_sync`, pas de registre dynamique. Le lot 1 peut démarrer. Spec et garde-fous en `06` §3.4.

---

## Lot 1 — Données et objectifs (1 à 2 jours)

**But** : les datapacks se chargent, `/bingo reload` fonctionne, on peut inspecter les objectifs chargés.

| # | Tâche | Réf. |
|---|---|---|
1.1 | Records `Objective`, `ObjectiveDisplay`, enum `ObjectiveType` | `01` §2 |
1.2 | Codecs DFU pour les 5 types de `target` | `01` §4 |
1.3 | `ObjectiveLoader` — `SimpleSynchronousResourceReloadListener` + compteur `revision` incrémenté à chaque chargement réussi | `01` §1, `06` §3.4 |
1.4 | Validation au chargement (6 règles, log WARN/ERROR, jamais de crash) | `01` §2 |
1.5 | `Pool`, `DifficultyProfile`, `Ruleset` + leurs codecs | `01` §6-8 |
1.6 | Résolution des pools (`entries` ∪ `include_tags` ∪ `inherit` − `exclude_tags`) | `01` §6 |
1.7 | `BoardGenerator` — tirage pondéré respectant la distribution, gestion des `conflicts` | `01` §7 |
1.8 | Comblement gracieux si un niveau est sous-alimenté (WARN, pas de crash) | `01` §7 |
1.9 | *(déjà livré : 45 objectifs — 13 N1 / 11 N2 / 12 N3 / 9 N4. Vérifier qu'ils chargent tous.)* | `01` §4 |
1.10 | *(déjà livré : 4 profils + `bingo:classic` + `bingo:default`)* | `01` §7-8 |
1.11 | *(déjà livré : `#bingo:roll_decoys`, 69 items)* | `04` §3 |
1.12 | `/bingo reload`, `/bingo debug dump` | `05` §4.1 |

**Recette** : `/bingo reload` charge les **45** objectifs sans warning ; `/bingo debug dump` sort une carte 5×5 cohérente avec la distribution du profil demandé, sur les 4 profils.

---

## Lot 2 — Boucle de jeu et HUD (2 à 3 jours)

**But** : une manche complète est jouable, sans vocal, sans JEI, sans animation.

| # | Tâche | Réf. |
|---|---|---|
2.1 | `BingoTeam`, gestion des équipes, `/bingo team *` | `05` §3-4 |
2.2 | Machine à états des phases + transitions | `06` §1 |
2.3 | Chrono (`startedAtMs`, `pausedAccumulatedMs`) | `06` §2 |
2.4 | `LINE_MASKS` (12 combinaisons) + `hasWon()` | `05` §1.1 |
2.5 | `tileScore()` et score d'équipe **dérivé**, jamais accumulé | `05` §2 |
2.6 | Index inversés `pendingByType` / `pendingCraftItems` / … | `06` §6 |
2.7 | Détecteurs des 5 types (`craft`, `find`, `kill_mob`, `death`, `action`) | `01` §4 |
2.8 | Registre `ActionTrigger` + les 8 triggers du lot 1 | `01` §4.5 |
2.9 | Paquets S2C : `objective_sync` (**avant** `board_sync` au `JOIN`), `board_sync`, `phase`, `tile_update`, `score_update`, `game_end`, `team_sync`, `open_board` | `06` §3, §3.4 |
2.10 | C2S `request_sync` | `06` §3.2 |
2.11 | `BingoBoardLayout` — **source unique** des constantes de layout | `03` §1 |
2.12 | `BingoHudOverlay` via `HudRenderCallback`, tout en `fill()` | `03` §1-2 |
2.13 | `BingoBoardScreen` réutilisant `BingoBoardLayout`, hit-test | `03` §1 |
2.14 | Keybinds `B` et toggle HUD | `03` §5 |
2.15 | Reste des commandes (`start`, `stop`, `pause`, `resume`, `reset`, `score`, `card`) | `05` §4 |
2.16 | `PersistentState` NBT + dégradation si objectif disparu | `06` §2 |
2.17 | Annonces et sons de partie | `05` §5 |

**Recette** : 4 joueurs, 2 équipes, `/bingo start normal`, valider des objectifs des 5 types, compléter une ligne, la victoire se déclenche. Redémarrer le serveur en pleine manche : l'état est restauré. `/bingo reset` depuis chaque phase ne laisse aucun résidu.

> **Recette à un seul joueur** : `/bingo debug solo` (`05` §4.2) monte la même manche avec une équipe `red` pourvue et une équipe `blue` vide. Tout est vérifiable seul sauf la lecture croisée des HUD : valider les 5 types en jouant, simuler l'adversaire avec `/bingo debug complete blue <index>`, déclencher la victoire avec `/bingo debug complete red <index>` sur une ligne. C'est le chemin à utiliser en développement ; la recette à 4 joueurs reste la validation finale.

---

## Lot 3 — Vocal et JEI (1 jour)

**But** : les deux intégrations externes.

| # | Tâche | Réf. |
|---|---|---|
3.1 | *(entrypoint et stub déjà faits au lot 0 — rien à refaire ici)* | `02` §3.1 |
3.2 | `BingoVoicechatPlugin` (`getPluginId`, `registerEvents`) | `02` §3.2 |
3.3 | `BingoVoiceManager` : groupe lobby `ISOLATED`, groupes d'équipe `OPEN` | `02` §3.3 |
3.4 | Branchement sur les transitions de phase | `02` §3.4 |
3.5 | Les 9 cas limites du tableau §4 | `02` §4 |
3.6 | `BingoJeiPlugin` (`@JeiPlugin`, stockage de l'`IJeiRuntime`, `onRuntimeUnavailable`) | `03` §3.2 |
3.7 | `BingoJeiBridge.showRecipe()` + **fallback tooltip** si aucune recette | `03` §3.1 |
3.8 | Routage du clic : gauche selon `interaction`, droit toujours tooltip | `03` §3 |
3.9 | Pop-up de description | `03` §3.3 |

**Recette** : dérouler intégralement les 7 étapes de `02` §6 — l'étape 5 (proximité **bidirectionnelle** entre équipes adverses) est celle qui valide `OPEN`. Puis cliquer une case `CRAFT` (JEI s'ouvre), une case `FIND minecraft:ancient_debris` (fallback tooltip), une case `KILL_MOB` (tooltip).

> ✅ **Livré** (29 juillet 2026). Deux écarts avec la spec, tous deux documentés dans le code :
>
> 1. **`jei_mod_plugin` ajouté à `fabric.mod.json`.** `docs/03` §3.2 affirmait que l'annotation suffisait ; c'est vrai sur Forge, faux sur Fabric — `FabricPluginFinder` lit un entrypoint. Sans lui le plugin n'est jamais instancié et JEI reste muet. Le doc est corrigé.
> 2. **Les groupes d'équipe sont créés `hidden`,** et une **réconciliation d'une passe par seconde** tourne pendant toute la manche. Les deux servent le même cas limite (n°4, « on écrase le groupe manuel pendant `RUNNING` ») : sans le premier, un adversaire rejoint le groupe rouge d'un clic depuis l'interface de SVC ; sans le second, le passage en spectateur (n°8) n'a aucun événement Fabric sur lequel s'accrocher en 1.20.1.
>
> Vérifié par lancement : événements vocaux enregistrés, `bind()` reposté sur le thread serveur, groupe lobby créé et joueur sans équipe assigné dedans en `RUNNING`, `onRuntimeAvailable` / `onRuntimeUnavailable` tous deux déclenchés. Le reste de la recette — surtout l'étape 5 — reste un test manuel à 4 joueurs.

---

## Lot 4 — Slot Machine et polish (1 à 2 jours)

**But** : le moment de lancement, et le mod devient présentable.

| # | Tâche | Réf. |
|---|---|---|
4.1 | Paquet `roll_start` (25 IDs + seed + startTimeMs) | `04` §1 |
4.2 | `RollAnimationState` client, tout dérivé de `elapsed` | `04` §6 |
4.3 | Phase A : swap 100 ms + click pitch 1.6 vol 0.25 | `04` §2.1 |
4.4 | Phase B : 5 verrous aux `t` spécifiés, pitch 1.0→1.8, ease-out du swap | `04` §2.2 |
4.5 | Effet de verrouillage (flash + punch d'échelle) | `04` §2.2 |
4.6 | Finale : `ui.toast.challenge_complete` + particules `FIREWORK` serveur `force=true` | `04` §2.3 |
4.7 | Gel par modificateur d'attribut **+ nettoyage déconnexion et démarrage serveur** | `04` §5 |
4.8 | Étincelles HUD 2D | `04` §4 |
4.9 | Mise en avant des combinaisons à 4/5 (bordure dorée) | `03` §2 |
4.10 | Textures (`panel.png`, `cell.png`, `check.png`) | `03` §6 |
4.11 | Sons `.ogg` de `sounds.json` | — |
4.12 | Traductions complètes `en_us` / `fr_fr` | — |
4.13 | Config client (`bingo-client.json`) : marges, échelle, visibilité | `05` §4.3 |

**Recette** : les 6 étapes de `04` §8. L'étape 6 (tuer le serveur pendant `ROLLING`) est celle qu'on oublie et qui produit le bug fantôme.

> ✅ **Livré** (29 juillet 2026). Quatre écarts avec la spec, tous documentés dans le code :
>
> 1. **`roll_ticks` n'est plus une constante.** `roll_start` transporte la durée réelle et le client recalcule ses seuils proportionnellement — c'est le « calcul proportionnel » que `04` §1 attendait pour lever la mise en garde. `roll_animation: false` réduit `ROLLING` à un tick au lieu d'imposer 3 s de phase vide.
> 2. **Les icônes défilantes viennent d'un hachage, pas d'un `Random(seed)` séquentiel.** `04` §3 et `04` §6 se contredisent : une séquence consommée n'est pas rejouable depuis le seul `elapsed`, or §6 exige que tout s'en dérive. Le hachage donne le même déterminisme inter-clients sans état à rembobiner quand une frame saute.
> 3. **`board_sync` transporte les `win_conditions`.** Sans elles, la bordure dorée du 4/5 mettrait en avant une diagonale sur un ruleset qui les a désactivées — elle annoncerait une victoire impossible.
> 4. **Aucun `.ogg` livré (4.11).** Les sept événements pointent sur des sons vanilla via `type: "event"`. Produire sept effets sonores originaux est un travail d'audio ; un alias donne un retour audible et se remplace en une ligne. Voir l'encadré de `04` §7 — et le piège du `_comment` qui fait rejeter tout le fichier.
>
> Textures (4.10) générées géométriquement : `panel.png` en 9-slice sur feuille 256×256 (contrainte de `drawNineSlicedTexture`), `cell.png` en atlas 2×2 de 4 états, `check.png`. Le tableau de `03` §6 annonçait `cell.png` en 64×32 pour 4 sprites de 18×18, ce qui ne rentre pas — d'où le 64×64.
>
> **Non vérifié en jeu** : l'animation elle-même. Elle exige `/bingo start` ou `/bingo debug solo`, donc une saisie clavier dans le client. Ce qui est vérifié : compilation, chargement des ressources sans avertissement, textures relues visuellement.

---

## Lot 5 — Finition (½ à 1 journée)

| # | Tâche |
|---|---|
5.1 | Datagen (`runDatagen`) pour les tags et les objectifs générables |
5.2 | Tests unitaires : `LINE_MASKS`, `tileScore()`, résolution de pool, distribution du `BoardGenerator` (statistique sur 10 000 tirages) |
5.3 | `/bingo config` complet |
5.4 | Passe de performance : profiler le scan `FIND` avec 8 joueurs |
5.5 | LICENSE + champs `contact` de `fabric.mod.json` |
5.6 | README joueur (distinct du README développeur) : commandes, écrasement du groupe vocal |

---

## Lot 6 — Passe réseau et état (½ journée)

**But** : confronter l'implémentation livrée aux §1 à §6 de `06`, et fermer ce que la spec ne nommait pas.

| # | Tâche | Réf. |
|---|---|---|
6.1 | Réalignement de la carte sur un rechargement de datapack en pleine manche | `06` §7.1 |
6.2 | Diffusion du catalogue même quand le rechargement échoue | `06` §7.2 |
6.3 | Cooldown serveur sur `request_sync` | `06` §7.3 |
6.4 | Refus à l'émission d'un paquet au-delà de `1 048 576` octets | `06` §7.4 |
6.5 | Plafonds d'allocation sur toutes les collections lues du réseau | `06` §7.5 |
6.6 | Borne d'index sur `tile_update` côté client | `06` §7.6 |
6.7 | Projections construites une fois par diffusion, plus une fois par destinataire | `06` §7.7 |

**Recette** : `/bingo start`, puis en pleine manche `/bingo reload` — d'abord avec un `count` modifié sur un objectif de la carte (la case doit se recaler, et se valider si le nouveau `count` est déjà atteint), ensuite avec ce fichier supprimé (WARN + manche terminée, aucune case fantôme). Vérifier qu'un `/bingo reload` hors manche ne change rien.

> ✅ **Livré** (30 juillet 2026). Aucun écart avec `06` §1-§6 n'a été trouvé : le protocole, la machine à états, la persistance et les index inversés étaient conformes. Les six correctifs portent tous sur des cas que la spec laissait ouverts, détaillés en `06` §7. Vérifié à la compilation ; les deux chemins de `06` §7.1 exigent une console (voir `06` §7.8).

---

## Ordre des dépendances

```
Lot 0 ──► Lot 1 ──► Lot 2 ──┬──► Lot 3 ──┐
                            │            ├──► Lot 5 ──► Lot 6
                            └──► Lot 4 ──┘
```

Les lots 3 et 4 sont **parallélisables** : ils ne partagent aucun fichier. Le lot 4 ne dépend du lot 2 que pour `BingoBoardLayout` et le canal `phase`.

---

## Ce qu'il faut écrire en premier dans chaque lot

Un réflexe qui fait gagner du temps sur ce projet précis : **commencer par la structure de données, finir par le rendu**. Le HUD est la partie la plus visible mais la moins structurante ; l'écrire avant que `Objective` et `completionMask` soient figés garantit de le réécrire.

Corollaire : le lot 2 doit produire un jeu jouable **avec un HUD laid**. Si le HUD est joli avant que la victoire ne se déclenche correctement, l'ordre a été inversé.
