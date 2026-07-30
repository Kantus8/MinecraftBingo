# 06 — Protocole réseau, machine à états et modèle de données

---

## ⚠️ API réseau en 1.20.1

**`CustomPayload` n'existe pas en 1.20.1.** L'API typée des payloads est arrivée en 1.20.5. En 1.20.1, le networking Fabric se fait avec un `Identifier` de canal et un `PacketByteBuf` écrit et lu à la main :

```java
// Serveur → client
ServerPlayNetworking.send(serverPlayer, CHANNEL_ID, buf);

// Enregistrement côté client
ClientPlayNetworking.registerGlobalReceiver(CHANNEL_ID,
    (client, handler, buf, responseSender) -> {
        // ⚠️ lire le buf ICI (thread réseau), puis client.execute(...) pour agir
    });
```

**Règle absolue** : décoder le `PacketByteBuf` **dans le handler réseau**, puis passer les objets déjà décodés à `client.execute(() -> ...)`. Le buf est libéré dès le retour du handler ; y toucher depuis le thread principal donne des données corrompues de façon intermittente — le pire type de bug à diagnostiquer.

---

## 1. Machine à états de partie

```
                    ┌──────────┐
          ┌────────►│  LOBBY   │◄───────────────────┐
          │         └────┬─────┘                    │
          │              │ /bingo start             │
          │              ▼                          │
          │         ┌──────────┐                    │
          │         │ ROLLING  │  3 s, gel actif    │
          │         └────┬─────┘                    │
          │              │ t = 3000 ms              │
          │              ▼                          │
          │         ┌───────────┐                   │
          │         │ COUNTDOWN │  5 s              │
          │         └────┬──────┘                   │
          │              │ countdown = 0            │
          │              ▼                          │
          │         ┌──────────┐   /bingo pause     │
          │         │ RUNNING  │◄──────────┐        │
          │         └────┬─────┘           │        │
          │              │            ┌────▼─────┐  │
          │              │            │  PAUSED  │  │
          │              │            └────┬─────┘  │
          │              │  /bingo resume  │        │
          │              │◄────────────────┘        │
          │              │ victoire · temps · stop  │
          │              ▼                          │
          │         ┌──────────┐                    │
          └─────────┤ FINISHED │────────────────────┘
            /bingo   └──────────┘   /bingo start
             reset
```

### Table des phases

| Phase | Chrono | Validation d'objectifs | Vocal | Gel |
|---|---|---|---|---|
`LOBBY` | — | non | global | non |
`ROLLING` | — | non | global | **oui** |
`COUNTDOWN` | — | non | global | non |
`RUNNING` | actif | **oui** | groupes d'équipe (`OPEN`) | non |
`PAUSED` | figé | non | global | non |
`FINISHED` | — | non | global | non |

`/bingo reset` est joignable **depuis toutes les phases** — c'est le filet de sécurité.

---

## 2. Modèle de données serveur

```java
/** État de partie, unique, attaché au MinecraftServer. */
public final class BingoGame {
    private GamePhase phase = GamePhase.LOBBY;

    // ── La carte : partagée, immuable après le tirage ──────────────
    private @Nullable Objective[] tiles;         // exactement 25, index = row*5+col
    private long rollSeed;
    private @Nullable Identifier difficultyId;
    private @Nullable Identifier rulesetId;

    // ── État par équipe ───────────────────────────────────────────
    private final Map<TeamId, BingoTeam> teams = new LinkedHashMap<>();

    // ── Chrono ────────────────────────────────────────────────────
    private long startedAtMs;
    private long pausedAccumulatedMs;
    private int timeLimitSeconds;
}

public final class BingoTeam {
    private final TeamId id;
    private final Text displayName;
    private final Formatting color;
    private final Set<UUID> members = new LinkedHashSet<>();

    private int completionMask;                  // 25 bits utiles (§1 de docs/05)
    private final int[] progress = new int[25];  // avancement partiel pour count > 1
    private final long[] completedAtMs = new long[25];
    // score = dérivé, jamais stocké
}
```

**Rien n'est stocké en double.** Le score se recalcule depuis `completionMask`, la détection de victoire aussi, l'avancement du chrono depuis `startedAtMs`. Un état minimal est un état qui ne peut pas devenir incohérent.

### Persistance

`PersistentState` attaché au `ServerWorld` de l'overworld, sérialisé en NBT :

| Clé NBT | Type | Contenu |
|---|---|---|
`phase` | String | nom de l'enum |
`tiles` | List\<String\> | 25 IDs d'objectifs |
`rollSeed` | Long | |
`difficulty`, `ruleset` | String | |
`startedAtMs`, `pausedMs` | Long | |
`teams` | List\<Compound\> | id, couleur, membres (UUID[]), mask, progress[], timestamps[] |

Au chargement : si un `tiles` référence un objectif disparu du datapack, **basculer la partie en `FINISHED`** avec un log WARN plutôt que de crasher ou de laisser une case fantôme.

> La même règle s'applique à un **rechargement de datapack en pleine manche**, qui n'est pas le chargement du monde et que cette section ne couvrait pas. Voir §7.1.

---

## 3. Paquets

Canaux : `bingo:<nom>`. Tous les identifiants de canal sont des constantes dans `BingoNetworking`.

### 3.1 Serveur → Client

| Canal | Quand | Charge utile |
|---|---|---|
`bingo:objective_sync` | Entrée en jeu (**avant** `board_sync`), après chaque rechargement de datapack | revision (int), N × projection d'affichage d'objectif (§3.4) |
`bingo:board_sync` | Entrée en jeu, `/bingo card`, resync | revision (int), phase (byte), 25 × Identifier, seed (long), rulesetId, timeLimit (varint), tempsRestant (varint), N équipes × {id, couleur, mask (int), progress[25] (byte[]), membres} |
`bingo:phase` | Chaque transition | phase (byte), timestamp serveur (long), payload optionnel selon la phase |
`bingo:roll_start` | Début de `ROLLING` | 25 × Identifier, seed (long), startTimeMs (long) |
`bingo:tile_update` | Validation d'une case | teamId (String), index (byte), newProgress (varint), completed (bool), completedAtMs (long) |
`bingo:score_update` | Après chaque `tile_update` | N × {teamId, score (varint), tileCount (byte)} |
`bingo:game_end` | Victoire ou temps écoulé | raison (byte : LINE \| TIME \| STOP), teamIds gagnants (String[]), indices de la combinaison gagnante (byte[5] ou vide), classement final |
`bingo:team_sync` | Changement de composition | N équipes × {id, couleur, nom, membres} |
`bingo:open_board` | `/bingo card` | (vide) |

### 3.2 Client → Serveur

| Canal | Quand | Charge utile |
|---|---|---|
`bingo:request_sync` | Ouverture de `BingoBoardScreen`, ou désync détectée | (vide) |

**Une seule commande C2S.** Tout le reste — rejoindre une équipe, démarrer, mettre en pause — passe par les **commandes Brigadier**, qui sont déjà un canal C2S validé, permissionné et journalisé par le serveur. Réimplémenter ces actions en paquets custom serait dupliquer la validation de permission, avec le risque de l'oublier d'un côté.

Ce canal est **étranglé des deux côtés** : 2 s côté client (une désync provoque des paquets qui provoqueraient une désync) et 1 s côté serveur, parce qu'un paquet vide y déclenche une réponse de plusieurs kilo-octets. Voir §7.3.

Le clic sur une case n'envoie **rien** : ouvrir JEI ou afficher un tooltip est purement client.

### 3.3 Conventions d'encodage

| Type logique | Écriture |
|---|---|
`Identifier` | `buf.writeIdentifier(id)` |
Phase | `buf.writeByte(phase.ordinal())` — l'ordre de l'enum devient contractuel |
`completionMask` | `buf.writeInt(mask)` — 4 octets pour 25 cases |
`progress[25]` | `buf.writeByteArray(bytes)` — clampé à 127, suffisant |
Liste | `buf.writeVarInt(size)` puis les éléments |
`Text` | `buf.writeText(text)` — **jamais** de String pré-formatée, sinon pas de traduction client |
UUID | `buf.writeUuid(uuid)` |

**Ne pas envoyer les objectifs complets** dans `board_sync` (poids, conflits, cible) : il n'envoie que les 25 `Identifier`, que le client résout dans le catalogue reçu une fois via `bingo:objective_sync` (§3.4). Un `board_sync` fait ainsi ~300 octets au lieu de plusieurs kilo-octets.

### 3.4 Synchronisation du catalogue d'objectifs

> **Décision actée le 29 juillet 2026** — c'était le point ouvert n°1 de `00` §4.
> Les objectifs restent chargés par un `SimpleSynchronousResourceReloadListener` côté serveur (`01` en tête), et le client reçoit une **projection d'affichage** via le paquet `bingo:objective_sync`.
> **Pas de registre dynamique synchronisé.**

#### Pourquoi pas le registre dynamique

`DynamicRegistries.registerSynced(key, codec, networkCodec, options…)` existe bien dans Fabric API 0.92.9 pour 1.20.1, et son codec réseau distinct permettrait la même projection. La synchronisation serait gratuite et arriverait avant l'entrée en jeu. Ça n'a pas suffi, pour une raison qui tranche :

**En 1.20.1, les registres dynamiques sont chargés par `RegistryLoader` pendant le `SaveLoading`, au chargement du monde.** `/reload` passe par `MinecraftServer.reloadResources()`, qui recharge les `DataPackContents` — recettes, advancements, tags, loot tables, fonctions — mais **pas** les registres dynamiques. C'est la raison pour laquelle une modification de datapack worldgen exige de ressortir du monde.

Le registre dynamique coûterait donc `/bingo reload` (tâche 1.12), et produirait pire qu'une absence de rechargement : un **demi-rechargement incohérent**, où `/bingo reload` reprendrait un pool ou un profil modifié — eux restent sur un reload listener — mais pas un objectif modifié. Un rechargement qui marche pour 3 fichiers sur 4 est plus piégeux qu'un rechargement absent. Et ce n'est pas qu'un confort de développement : c'est l'outil qu'un admin utilise pour ajuster un pool entre deux manches sans éjecter les joueurs.

Deux arguments qui semblaient pousser vers le registre et qui ne tiennent pas ici :

- **« Les tags viennent gratuitement. »** Non : `01` §2 définit `tags` comme un **champ de l'objectif**, résolu par la logique de pool de `01` §6. Ce n'est pas le système de tags vanilla (`data/<ns>/tags/<registre>/`). L'index inversé tag → objectifs est à écrire dans les deux cas.
- **« Un client sans le datapack afficherait des cases vides. »** C'est vrai de la troisième option — laisser le client lire ses propres fichiers — pas de celle-ci : le client reçoit tout ce qu'il affiche dans le paquet, y compris pour un datapack tiers qu'il ne possède pas.

#### Projection d'affichage

Un objectif complet ne part **jamais** sur le réseau. Le client ne fait que du rendu (`06` §5), donc il reçoit :

| Champ | Encodage | Pourquoi le client en a besoin |
|---|---|---|
`id` | `writeIdentifier` | clé de résolution depuis `board_sync` |
`type` | `writeByte(ordinal)` | routage du clic (`01` §5) |
`level` | `writeByte` | multiplicateur affiché, bordure de case |
`count` | `writeVarInt` | rendu de l'avancement `3/5` |
`icon` | `writeIdentifier` | `DrawContext#drawItem` |
`icon_count` | `writeVarInt` | badge numérique |
`name`, `description` | `writeText` | **jamais** de String pré-formatée (§3.3) |
`interaction`, `jei_role` | `writeByte` | routage JEI vs tooltip |
`target` | booléen de présence + union `item` \| `tag` | **seulement** pour `CRAFT` et `FIND`, pour `BingoJeiBridge` (`03` §3) |

Restent serveur : `weight`, `conflicts`, `requires_dimension`, `announce`, `points_base`, et la cible des types `KILL_MOB` / `DEATH` / `ACTION`.

**Taille** : la limite d'un `CustomPayloadS2CPacket` en 1.20.1 est de `1 048 576` octets. À ~150 octets par objectif, les 45 livrés font ~7 Ko et le plafond n'est atteint que vers 7 000 objectifs. Découper en lots seulement si un datapack tiers y arrive un jour.

#### Les quatre garde-fous

1. **Ordre à l'entrée en jeu** : `objective_sync` **avant** `board_sync` dans le handler `JOIN` (§4). L'ordre est garanti sur la connexion, il suffit de séquencer les envois — un `board_sync` reçu avant son catalogue ne peut rien afficher.
2. **`int revision`**, incrémenté à chaque chargement réussi du loader, présent dans `objective_sync` **et** `board_sync`. Le client compare ; en cas d'écart il envoie `request_sync`, qui existe déjà (§3.2). Un entier ferme le trou de désynchronisation.
3. **ID inconnu = case placeholder**, jamais un crash : `minecraft:barrier` et l'ID brut en tooltip. C'est le miroir client de la règle imposée au serveur en §2, et du fallback de `01` §2 règle 3.
4. **Le catalogue client est purement présentationnel.** Aucune décision de jeu ne s'appuie dessus — pas de validation d'objectif côté client, jamais.

#### Quand rouvrir la question

Si le client doit un jour **valider** des objectifs lui-même, ou si un plugin JEI a besoin de la cible complète de tous les types, le registre dynamique redevient le bon outil. Tant que le client ne fait que du rendu, le paquet est plus simple et respecte la frontière que §5 trace déjà.

---

## 4. Points de synchronisation

| Moment | Paquet envoyé |
|---|---|
`ServerPlayConnectionEvents.JOIN` | `objective_sync`, **puis** `board_sync` + `team_sync` (§3.4 garde-fou 1) |
Transition de phase | `phase` à tous |
Début de `ROLLING` | `roll_start` à tous |
Validation d'une case | `tile_update` + `score_update` à tous |
Fin de partie | `game_end` à tous |
Changement d'équipe | `team_sync` à tous |
`/reload` ou `/bingo reload` | `objective_sync` (revision incrémentée), puis `board_sync` à tous |
Client détecte une incohérence | `request_sync` → réponse `board_sync` |

**Aucun paquet par tick.** Le chrono est calculé côté client à partir de `startedAtMs` et de la phase reçue ; il ne se resynchronise qu'aux transitions. Une dérive de quelques centaines de millisecondes sur l'affichage d'un chrono est invisible, et ça épargne 20 paquets/seconde/joueur.

---

## 5. Répartition entre les source sets

Rappel : `splitEnvironmentSourceSets()` est actif (voir `README.md`). Toute référence à une classe client depuis `src/main` casse la compilation — c'est le garde-fou.

| Classe | Source set | Raison |
|---|---|---|
`BingoGame`, `BingoTeam`, `Objective` | `main` | modèle partagé, aucune classe client |
`BingoNetworking` (IDs de canaux) | `main` | constantes partagées |
Handlers d'envoi S2C | `main` | serveur |
`BingoVoicechatPlugin` | `main` | entrypoint chargé sur serveur dédié |
`BingoVoiceManager` | `main` | logique de groupes vocaux, 100 % serveur (`docs/02` §3.3) |
`BingoJeiPlugin` | `main` | `@JeiPlugin` scanné par annotation ; l'API JEI est en `modCompileOnly` sur `main` |
`BingoHudOverlay`, `BingoBoardScreen`, `BingoBoardLayout` | `client` | rendu |
`BingoJeiBridge` (appel `getRecipesGui`) | `client` | touche `MinecraftClient` |
Réception des paquets S2C | `client` | `ClientPlayNetworking` |
`RollAnimationState` | `client` | animation locale |

Le découpage JEI mérite une explication : la **classe de plugin** (`@JeiPlugin`, `IModPlugin`) vit dans `main` parce que JEI la découvre par scan d'annotations sur l'ensemble du jar, et parce qu'elle ne fait que stocker l'`IJeiRuntime`. Le **pont qui ouvre la GUI** vit dans `client`, parce qu'il appelle `MinecraftClient.getInstance()`.

---

## 6. Ordre de validation d'un objectif (chemin critique)

```
événement de jeu (craft, kill, mort, tick d'inventaire, trigger)
        │
        ├─ 1. phase == RUNNING ?                       sinon → abandon
        ├─ 2. le joueur appartient-il à une équipe ?    sinon → abandon
        ├─ 3. parcourir les cases NON validées par cette équipe
        │      dont le type correspond à l'événement
        ├─ 4. l'objectif matche-t-il l'événement ?      (target, count)
        ├─ 5. progress[index]++ ; si >= count → set bit dans completionMask,
        │      completedAtMs[index] = now
        ├─ 6. envoyer tile_update + score_update
        ├─ 7. hasWon(completionMask) ?
        │      └─ oui → phase = FINISHED, game_end, vocal → lobby
        └─ 8. son + annonce en chat selon la config
```

**L'étape 3 est le point chaud.** Ne jamais parcourir les 25 objectifs pour chaque événement de jeu. Maintenir par équipe un index inversé, construit une fois au tirage et mis à jour à chaque validation :

```java
Map<ObjectiveType, List<Integer>> pendingByType;   // par équipe
Map<Item,       List<Integer>> pendingCraftItems;  // par équipe
Map<Item,       List<Integer>> pendingFindItems;   // par équipe
Map<EntityType<?>, List<Integer>> pendingKills;    // par équipe
```

Le coût d'un event devient un lookup de hash, pas un balayage. Sur `FIND`, qui est scanné toutes les 10 ticks pour chaque joueur, la différence n'est pas cosmétique.

---

## 7. Passe de durcissement réseau et état

> ✅ **Livrée** (30 juillet 2026), après les lots 0 à 5. Le protocole et la machine à états étaient conformes aux §1 à §6 ; cette passe a fermé six trous que la spec ne nommait pas, tous vérifiés à la compilation.

### 7.1 Rechargement de datapack en pleine manche

`§3.4` fait de `/bingo reload` l'outil qu'un admin utilise pour ajuster un pool **entre deux manches**. Rien n'empêche de le lancer **pendant** une manche, et c'était le trou : les 25 cases tenaient des instances d'`Objective` chargées par le rechargement précédent, devenues orphelines. Le serveur continuait de valider l'ancienne définition pendant que le client, qui venait de recevoir le nouveau catalogue, dessinait le nouveau `count` — ou une case `minecraft:barrier` si l'objectif avait disparu.

`BingoGame.onDataReload()` réaligne la carte, branché sur `END_DATA_PACK_RELOAD` **entre** `objective_sync` et `board_sync` (les `tile_update` qu'il peut émettre doivent tomber sur un client dont le catalogue est déjà à jour) :

| Cas | Comportement |
|---|---|
| Les 25 identifiants répondent | cases remplacées par les définitions fraîches, index inversés reconstruits, avancement réappliqué contre le nouveau `count` |
| Un identifiant a disparu | WARN + carte abandonnée, comme au chargement du monde (§2) |
| Hors manche (`tiles` vide) | aucun effet |

Deux points méritent d'être notés :

- **La réapplication de l'avancement est nécessaire, pas cosmétique.** Un `count` abaissé de 5 à 2 sur une case à `3/5` la laisserait afficher `3/2` jusqu'à la fin de la manche, jamais validable pour un type sans rescan (`craft`, `kill_mob`). Passer l'avancement courant dans `applyProgress` la coche séance tenante, avec les paquets et la détection de victoire qui en découlent. Sans changement de définition, c'est un no-op : aucun paquet ne part. Elle n'a lieu qu'en `RUNNING`, comme toute validation (étape 1 de §6) : en `PAUSED` la validation est suspendue, et en `FINISHED` cocher une case rejouerait un `game_end` qui réécrirait le vainqueur d'une manche déjà jouée. Le recalage des définitions, lui, a lieu dans toutes les phases — c'est de l'affichage.
- **La phase d'arrivée d'un abandon dépend de la phase de départ,** parce que la machine à états de §1 n'a **pas** d'arête `ROLLING → FINISHED` ni `COUNTDOWN → FINISHED`. Depuis `RUNNING` ou `PAUSED`, la manche a une histoire et un classement : elle se termine (`FINISHED`, raison `STOP`). Avant le départ effectif, rien n'a été joué et le salon est la seule fin cohérente (`LOBBY`). Élargir la table des transitions pour uniformiser aurait été inverser la priorité entre le code et le diagramme de §1.

### 7.2 Diffusion sur rechargement en échec

`END_DATA_PACK_RELOAD` arrive avec un booléen de succès, et se taire quand il est faux était le réflexe naturel — mais faux. Les reload listeners du mod ont **déjà** remplacé leur contenu et incrémenté `revision` avant que le rechargement ne casse : ne rien diffuser laisse chaque client sur une révision périmée jusqu'au prochain `board_sync`, alors que le serveur a bel et bien changé de catalogue. Le catalogue part donc dans les deux cas, avec un WARN quand le rechargement a échoué.

### 7.3 Étranglement de `request_sync`

Un cooldown de 1 s par joueur côté serveur (table nettoyée à la déconnexion). Le client s'étrangle déjà à 2 s : un client légitime ne rencontre jamais ce plafond, il est là pour celui qui ne l'est pas. Sans lui, un paquet vide de quelques octets fait émettre au serveur le catalogue entier, autant de fois qu'on le demande.

### 7.4 Plafond de charge utile à l'émission

§3.4 cite la limite de `1 048 576` octets d'un `CustomPayloadS2CPacket` sans dire qui la vérifie — et la réponse est : **personne, à l'écriture**. Minecraft ne contrôle la taille qu'à la lecture, donc un catalogue trop gros part et c'est le client qui lève, se faisant déconnecter par un message qui ne nomme ni le canal ni la taille. L'émission vérifie maintenant la taille encodée et refuse le paquet, en journalisant le canal et le dépassement. Un joueur sans catalogue garde des cases placeholder — ce que le garde-fou 3 prévoit déjà — au lieu d'être éjecté.

### 7.5 Plafonds d'allocation sur toutes les collections lues

La règle « une taille lue du réseau ne dimensionne jamais une allocation sans plafond » n'était appliquée qu'aux 25 cases de `board_sync` et de `roll_start`. Elle couvre désormais les équipes (64), les membres (512), les entrées de score (64), les gagnants (64), la combinaison gagnante (25) et le catalogue (16 384).

### 7.6 Borne d'index sur `tile_update`

L'index d'une case sert de décalage de bit dans le masque de complétion côté client, et **en Java un décalage est pris modulo 32** : `1 << 32` vaut `1`. Un index hors grille ne produisait donc pas d'erreur mais cochait silencieusement une autre case — la 0 pour un index 32. Le paquet est refusé et journalisé, comme miroir d'index du garde-fou 3, qui ne portait que sur les identifiants.

### 7.7 Ce qui a aussi changé, sans être un trou

Les projections (`board_sync`, `phase`, `team_sync`, `score_update`) sont construites **une fois par diffusion** et non une fois par destinataire. Le buffer, lui, reste par destinataire — c'est l'invariant de `CustomPayloadS2CPacket#write` documenté dans `BingoServerNetworking`, et il n'a pas bougé. Ce qui était répété pour rien, c'était le calcul : un `score_update` redérive tout le classement depuis les masques, huit fois pour huit joueurs.

### 7.8 Ce qui reste à vérifier en jeu

La conformité est vérifiée à la compilation. Les deux chemins de §7.1 demandent une console : `/bingo start` puis, en pleine manche, `/bingo reload` avec un `count` modifié dans un objectif de la carte (§7.1 ligne 1), puis avec ce fichier d'objectif supprimé (§7.1 ligne 2).
