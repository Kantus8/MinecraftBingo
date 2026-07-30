# 00 — Vue d'ensemble et index du dossier de cadrage

**Projet** : Minecraft Bingo · Fabric 1.20.1 · mod id `bingo`
**Date de cadrage** : 29 juillet 2026
**État** : cadrage validé, prêt pour l'implémentation

---

## Index

| Doc | Contenu | À lire avant d'écrire… |
|---|---|---|
[`01-DATAPACK-SCHEMA.md`](01-DATAPACK-SCHEMA.md) | Schéma JSON des objectifs, 5 types, 4 niveaux, pools, profils de difficulté, rulesets | le modèle de données, les codecs, le loader |
[`02-VOICECHAT-SPEC.md`](02-VOICECHAT-SPEC.md) | Machine à états vocale, `Group.Type.OPEN`, API SVC 2.5.x, cas limites | `BingoVoicechatPlugin`, `BingoVoiceManager` |
[`03-HUD-JEI-SPEC.md`](03-HUD-JEI-SPEC.md) | Layout pixel du HUD 5×5, états de case, écran cliquable, routage JEI/tooltip | tout le code client de rendu |
[`04-SLOT-MACHINE-VFX.md`](04-SLOT-MACHINE-VFX.md) | Timeline 3 s, ralentissement, verrouillage par ligne, SFX/VFX, gel des joueurs | l'animation de tirage |
[`05-RULES-SCORING-COMMANDS.md`](05-RULES-SCORING-COMMANDS.md) | 12 combinaisons gagnantes, formule de score, égalités, arbre des commandes | la logique de partie et les commandes |
[`06-NETWORK-AND-STATE.md`](06-NETWORK-AND-STATE.md) | Phases, modèle de données, paquets S2C/C2S, persistance, répartition des source sets | le networking et la structure des classes |
[`07-IMPLEMENTATION-PLAN.md`](07-IMPLEMENTATION-PLAN.md) | Backlog ordonné en 5 lots, avec critères de recette | rien — c'est la feuille de route |

---

## 1. Le jeu en un paragraphe

Deux à quatre équipes de **deux joueurs** s'affrontent sur une **grille 5×5 partagée** de 25 objectifs Minecraft tirés au sort. La première équipe à compléter une **ligne, colonne ou diagonale** gagne. Chaque objectif rapporte des points selon son niveau de difficulté (×1, ×2, ×4, ×8). Pendant la manche, chaque binôme est isolé dans un **canal vocal privé** tout en conservant le **chat de proximité** avec les adversaires croisés en jeu — entendre l'équipe adverse fouiller la même grotte fait partie du jeu.

## 2. Piliers de design

1. **L'information est une ressource.** Le HUD révèle la progression adverse ; le vocal de proximité laisse fuiter les intentions. Ce qu'on sait vaut autant que ce qu'on possède.
2. **La géométrie prime sur le score.** On gagne en alignant, pas en accumulant. Choisir entre « facile et aligné » et « rentable mais dispersé » est la décision centrale de chaque manche.
3. **Le duo, pas le solo.** À deux joueurs, la seule optimisation viable est la division du travail — donc la communication. Le vocal n'est pas un accessoire, c'est le cœur mécanique.
4. **Le tirage est un spectacle.** Les 3 secondes de Slot Machine ne servent aucune mécanique. Elles existent pour créer le moment collectif qui lance la manche.

## 3. Décisions actées

| Sujet | Décision | Où c'est détaillé |
|---|---|---|
Carte | **Une seule, partagée** par toutes les équipes | `01` §9 |
Verrouillage de case | **Aucun** — chaque équipe coche sa propre grille sur les mêmes objectifs | `01` §9, `05` §1.2 |
Difficulté | **Profil de distribution mixte** des 5 niveaux sur les 25 cases | `01` §7 |
Victoire | Première ligne / colonne / diagonale — 12 combinaisons | `05` §1 |
Score | `PointsBase × 2^(niveau−1)`, base 100, **ne décide pas de la victoire** | `05` §2 |
Vocal en manche | Un groupe `Group.Type.OPEN` par équipe | `02` §1 |
Vocal hors manche | Un groupe `ISOLATED` unique contenant tout le monde | `02` §2 |
Bascule vocale | À la **fin du countdown**, pas au `/bingo start` | `02` §2 |
Clic sur le HUD | Via un `Screen` superposé au HUD (`B`) — un overlay n'est jamais cliquable | `03` en tête |
Animation | Déterministe par seed, un seul paquet | `04` §1 |
Dépendances | Simple Voice Chat **et** JEI en `depends` (dur) | `README.md` |
Source sets | Split client/commun actif | `06` §5
Objectifs vers le client | Loader de datapack + paquet `bingo:objective_sync` — **pas** de registre dynamique | `06` §3.4 |

## 4. Points laissés ouverts

À trancher pendant l'implémentation, chacun documenté à l'endroit concerné :

1. ~~**Synchronisation du registre d'objectifs**~~ — **TRANCHÉ le 29 juillet 2026** : loader de datapack + paquet `bingo:objective_sync` portant une projection d'affichage, pas de registre dynamique. Le registre dynamique aurait coûté `/bingo reload`, les registres dynamiques n'étant pas rechargés par `/reload` en 1.20.1. Détail et garde-fous en `06` §3.4. *(Numérotation conservée : les points 2 à 6 gardent leur numéro.)*
2. **Rôle JEI pour `FIND`** (`03` §3.1). `OUTPUT` par défaut, mais à retester en jeu sur des objectifs sans recette — le fallback tooltip suffit-il, ou faut-il un mode « usages » ?
3. **Textures** (`03` §6). Le lot 2 se fait entièrement en `fill()` ; les assets ne bloquent rien.
4. **Bonus de combinaison** (`05` §2.3). Désactivé par défaut, à n'activer que si un mode « partie longue » apparaît.
5. **`roll_ticks` configurable** (`04` §1). La timeline est calibrée en dur pour 60 ticks. Écrire un calcul proportionnel, ou retirer la clé du ruleset.
6. **Schéma des cartes pré-écrites** (`01` §1). `data/bingo/cards/` est réservé mais non spécifié, hors périmètre des lots 0 à 5.

## 5. Glossaire

| Terme | Sens dans ce dossier |
|---|---|
**Case** / *tile* | Une des 25 positions de la grille. Index `row * 5 + col`, de 0 à 24. |
**Objectif** | La définition JSON assignée à une case. |
**Combinaison** | Une des 12 lignes gagnantes (5 lignes, 5 colonnes, 2 diagonales). |
**Niveau** | Difficulté d'un objectif, 1 à 4. Pilote le multiplicateur de score. |
**Profil de difficulté** | Distribution des niveaux sur les 25 cases (`easy` … `extreme`). |
**Ruleset** | Préréglage de partie : durée, victoire, vocal, taille d'équipe. |
**Pool** | Ensemble d'objectifs éligibles au tirage. |
**Manche** / *round* | Une partie, de `ROLLING` à `FINISHED`. |
**Leurre** / *decoy* | Icône affichée pendant l'animation de tirage sans être sur la carte finale. |
**`completionMask`** | Entier de 25 bits représentant les cases validées par une équipe. |

## 6. Contraintes techniques à ne pas oublier

Huit pièges spécifiques à cette combinaison de versions, chacun capable de coûter une demi-journée :

1. **`CustomPayload` n'existe pas en 1.20.1** — le networking se fait en `Identifier` + `PacketByteBuf`, et le buf doit être décodé dans le thread réseau. (`06` en tête)
2. **Un overlay HUD ne reçoit jamais de clic** — d'où l'écran superposé. (`03` en tête)
3. **L'API Simple Voice Chat n'est pas remappée** — `compileOnly`, pas `modCompileOnly`, et sa version (`2.5.36`) est indépendante de celle de Minecraft. (`README.md`, `02`)
4. **Le gel par modificateur d'attribut doit être nettoyé à la déconnexion et au démarrage du serveur** — sinon un crash pendant `ROLLING` laisse des joueurs immobiles. (`04` §5)
5. **La contrainte `depends` sur `voicechat` doit être préfixée par la version de Minecraft** — le mod distribué s'annonce `1.20.1-2.5.36`, ce qui se lit en semver comme « 1.20.1 + pre-release `2.5.36` ». Une pre-release étant inférieure à sa release, `>=2.5.0` ne matche jamais et Loader refuse de démarrer avec `only the wrong version is present`. Le plancher s'écrit `>=1.20.1-2.5.0`. (`gradle.properties`)
6. **Les artefacts Maven de JEI mélangent deux jeux de mappings** — `jei-<mc>-common-api` et les modules `common`/`core`/`lib`/`gui` sont en mappings **Mojang** (pour Forge) ; seuls `-fabric`, `-fabric-api` et `-common-api-intermediary` sont en intermediary. Comme Loom remappe tous les mods en un seul passage de tiny-remapper **avec dédoublonnage des classes entre les entrées**, une seule variante Mojang sur le classpath suffit à polluer la sortie : `NoClassDefFoundError net/minecraft/resources/ResourceLocation` au démarrage. D'où les `transitive = false` de `build.gradle`. **Corollaire** : la sortie polluée est mise en cache et la clé de cache ignore les transitives — après correction, purger `.gradle/loom-cache/remapped_mods/*/mezz`, sinon le crash est identique et la correction paraît inopérante.
7. **Pas de clé `_comment` dans `sounds.json`** — contrairement aux fichiers `lang` (map String→String), `sounds.json` est parsé en map *clé → objet*. Une valeur String y casse le parsing et fait rejeter **tout** le fichier avec un seul `WARN Invalid sounds.json`, sans indiquer la ligne : aucun son du mod n'existe alors, silencieusement.
8. **Identifiant d'événement sonore ≠ chemin du `.ogg`** — dans `sounds.json`, la clé de l'objet donne l'événement (`bingo:ui.objective_complete`, à points) tandis que `sounds[].name` désigne le fichier (`bingo:ui/objective_complete`, à slash). Utiliser la forme à slash dans le code ne résout aucun son, sans erreur au démarrage. (`04` §7)
