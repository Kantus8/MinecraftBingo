# 02 — Spécification Simple Voice Chat

> API : `de.maxhenkel.voicechat:voicechat-api:2.5.36` (`compileOnly`, aucune classe Minecraft)
> Mod requis à l'exécution : Simple Voice Chat `1.20.1-2.5.x`, mod id `voicechat`

---

## 1. Le mécanisme clé : `Group.Type.OPEN`

Simple Voice Chat expose trois types de groupe, et c'est **le seul point à bien comprendre** pour que la spec fonctionne :

| Type | Les membres du groupe s'entendent | Les membres entendent les joueurs proches hors groupe | Les joueurs proches entendent les membres |
|---|---|---|---|
| `NORMAL` | oui, sans distance | **oui** | **non** (asymétrique) |
| `OPEN` | oui, sans distance | **oui** | **oui** |
| `ISOLATED` | oui, sans distance | non | non |

Le besoin — *« isolation en groupe privé d'équipe, tout en gardant le son de proximité actif avec les autres équipes »* — correspond **exactement** à `OPEN`, sans aucun code de mixage audio à écrire.

`NORMAL` est un piège : il crée une asymétrie (on entend les adversaires, ils ne nous entendent pas) qui passe pour un bug côté joueur.

---

## 2. Machine à états vocale

```
                    ┌──────────────────────────────────────┐
                    │  LOBBY / PAUSED / FINISHED           │
                    │  → 1 groupe unique "Bingo Lobby"     │
                    │    contenant TOUS les joueurs        │
                    │    Type: ISOLATED                    │
                    │    Effet : tout le monde s'entend,   │
                    │    sans distance                     │
                    └──────────────┬───────────────────────┘
                                   │  /bingo start
                          ┌────────▼─────────┐
                          │ ROLLING          │  vocal inchangé (lobby)
                          │ COUNTDOWN        │  l'animation dure 3 s,
                          └────────┬─────────┘  basculer maintenant = coupure sèche
                                   │  fin du countdown
                    ┌──────────────▼───────────────────────┐
                    │  RUNNING                             │
                    │  → 1 groupe PAR ÉQUIPE               │
                    │    "Bingo · <Équipe>"                │
                    │    Type: OPEN                        │
                    │    Effet : le binôme s'entend sans   │
                    │    distance + proximité active dans  │
                    │    les deux sens avec tout le monde  │
                    └──────────────┬───────────────────────┘
                                   │  /bingo pause · stop · fin de manche
                                   ▼
                            retour au groupe lobby
```

### Table de transition

| Phase | Groupe assigné | Type | Effet perçu |
|---|---|---|---|
| `LOBBY` | `Bingo Lobby` (unique) | `ISOLATED` | Global : tout le monde s'entend partout |
| `ROLLING` | inchangé (lobby) | `ISOLATED` | Global — l'animation reste un moment collectif |
| `COUNTDOWN` | inchangé (lobby) | `ISOLATED` | Global |
| `RUNNING` | `Bingo · <équipe>` | `OPEN` | Binôme sans distance + proximité bidirectionnelle |
| `PAUSED` | `Bingo Lobby` | `ISOLATED` | Global — permet l'arbitrage à voix haute |
| `FINISHED` | `Bingo Lobby` | `ISOLATED` | Global — debrief |

**Pourquoi le lobby est `ISOLATED` et pas `OPEN`** : le groupe contient déjà tout le monde. `OPEN` ajouterait un canal de proximité redondant par-dessus le canal global — même audio joué deux fois, avec un risque d'écho perceptible. `ISOLATED` est le choix propre.

**Pourquoi la bascule se fait à la fin du countdown et pas au `/bingo start`** : basculer pendant l'animation Slot Machine coupe les réactions en direct au tirage, qui sont un des meilleurs moments de la manche. Les 3 s d'animation + le countdown restent en vocal global.

---

## 3. Implémentation

### 3.1 Enregistrement du plugin

Ajouter dans `src/main/resources/fabric.mod.json` :

```json
"entrypoints": {
  "voicechat": [ "com.bingo.mod.integration.voicechat.BingoVoicechatPlugin" ]
}
```

`BingoVoicechatPlugin` vit dans **`src/main/java`** (source set commun) : la logique de groupe est 100 % serveur, et l'entrypoint doit être chargeable sur un serveur dédié.

### 3.2 Squelette du plugin

```java
package com.bingo.mod.integration.voicechat;

public class BingoVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "bingo";                     // = mod id, doit être unique
    }

    @Override
    public void initialize(VoicechatApi api) {
        // rien à faire ici : on veut l'API serveur, pas l'API générique
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(PlayerConnectedEvent.class,        this::onPlayerConnected);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        BingoVoiceManager.get().bind(event.getVoicechat());   // VoicechatServerApi
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        // Un joueur qui rejoint en cours de partie doit atterrir dans le bon groupe
        BingoVoiceManager.get().reapply(event.getConnection());
    }
}
```

### 3.3 `BingoVoiceManager` — le cœur de la logique

Singleton serveur, seul propriétaire des groupes. Aucune autre classe ne touche à l'API vocale.

```java
public final class BingoVoiceManager {

    @Nullable private VoicechatServerApi api;
    @Nullable private Group lobbyGroup;
    private final Map<TeamId, Group> teamGroups = new HashMap<>();

    /** Appelé par le plugin au démarrage du serveur vocal. */
    public void bind(VoicechatServerApi api) {
        this.api = api;
        this.lobbyGroup = api.groupBuilder()
                .setName("Bingo Lobby")
                .setPersistent(true)              // survit au départ du dernier joueur
                .setType(Group.Type.ISOLATED)
                .build();
        // Un groupe persistant existe dès le .build()
    }

    /** Appelé à chaque transition de phase (§2). */
    public void onPhaseChanged(GamePhase phase) {
        if (api == null) return;                  // SVC absent ou pas encore démarré
        switch (phase) {
            case RUNNING -> assignTeamGroups();
            default      -> assignLobbyGroup();
        }
    }

    private void assignTeamGroups() {
        for (BingoTeam team : game.teams()) {
            Group group = teamGroups.computeIfAbsent(team.id(), id -> api.groupBuilder()
                    .setName("Bingo · " + team.displayName())
                    .setPersistent(true)
                    .setType(Group.Type.OPEN)     // ← le point crucial
                    .build());
            for (UUID member : team.members()) {
                moveTo(member, group);
            }
        }
        // Les spectateurs et les joueurs sans équipe restent dans le lobby
        for (UUID uuid : game.playersWithoutTeam()) moveTo(uuid, lobbyGroup);
    }

    private void moveTo(UUID uuid, @Nullable Group target) {
        VoicechatConnection conn = api.getConnectionOf(uuid);
        if (conn == null) return;                 // joueur absent, ou sans le mod client
        if (Objects.equals(conn.getGroup(), target)) return;  // déjà bon : ne rien faire
        conn.setGroup(target);
    }
}
```

### 3.4 Points d'appel

| Événement | Appel |
|---|---|
| Serveur vocal démarré | `bind(api)` |
| Transition de phase | `onPhaseChanged(newPhase)` |
| Joueur rejoint/change d'équipe | `reapplyFor(player)` |
| Joueur se connecte | `reapply(connection)` |
| Serveur s'arrête | `unbind()` — libérer les groupes non persistants |

---

## 4. Cas limites — traiter explicitement

| Situation | Comportement attendu |
|---|---|
| **Simple Voice Chat absent** | Impossible : `depends` dans `fabric.mod.json`. Le mod ne démarre pas. |
| **`api == null`** (serveur vocal pas encore prêt) | Toutes les méthodes sortent silencieusement. Rejouer l'assignation sur `VoicechatServerStartedEvent`. |
| **Joueur sans le mod client** | `getConnectionOf()` renvoie `null`. Ignorer. Le joueur joue sans vocal, la partie continue. |
| **Joueur déjà dans un groupe manuel** | On **écrase** pendant `RUNNING` (le vocal d'équipe est une règle de jeu, pas une préférence). À la sortie de partie, on remet dans le lobby — on ne restaure pas l'ancien groupe. Documenter dans le README joueur. |
| **Déconnexion / reconnexion en manche** | `PlayerConnectedEvent` → `reapply()`. Le joueur retrouve son groupe d'équipe. |
| **Changement d'équipe en manche** | Interdit par défaut. Si autorisé par config : quitter l'ancien groupe puis rejoindre le nouveau dans le même tick. |
| **Équipe vidée en cours de manche** | Le groupe reste (persistant) et se remplit si un joueur revient. Nettoyage à `/bingo reset`. |
| **Joueur passe spectateur** | Retour au groupe lobby : un spectateur entend tout le monde. |
| **`/bingo reset`** | Dissoudre tous les groupes d'équipe, remettre tout le monde dans le lobby, vider `teamGroups`. |

### Deux choix d'implémentation que ce tableau appelle

**Les groupes d'équipe sont créés `hidden`** (`Group.Builder#setHidden(true)`). Sans cela, ils apparaissent dans la liste de groupes de Simple Voice Chat et n'importe quel adversaire les rejoint d'un clic — ce qui annule la séparation que toute cette spec construit. Le groupe lobby, lui, reste visible : il contient déjà tout le monde.

**Une réconciliation tourne à 1 Hz tant qu'une manche est engagée** : chaque seconde, `BingoVoiceManager` recalcule le groupe attendu de chaque joueur connecté et ne bouge que ceux qui ont dérivé. C'est ce qui fait tenir trois lignes du tableau sans événement dédié — l'écrasement d'un groupe rejoint à la main (ligne 4), le passage en spectateur (ligne 8, dont Fabric 1.20.1 n'expose aucun événement), et toute connexion vocale qu'un `PlayerConnectedEvent` manqué aurait laissée au mauvais endroit. Le coût est d'un lookup de connexion par joueur et par seconde.

Hors manche, la réconciliation est **inactive** : le lobby est un point de passage, pas une prison. Un joueur qui se crée un groupe entre deux parties a le droit d'y rester.

---

## 5. Ce qui est volontairement hors périmètre

- **Pas de `MicrophonePacketEvent`** : aucun traitement audio custom, aucun filtre, aucun effet. On se contente d'assigner des groupes — c'est SVC qui fait tout le mixage. C'est ce qui rend cette intégration robuste.
- **Pas de canal audio custom** (`LocationalAudioChannel`) : pas de besoin d'audio positionnel généré par le mod.
- **Pas de plugin client** dans le lot 1. Un `VolumeCategory` dédié et une icône custom sont un raffinement du lot 4 (voir `docs/07`), pas une dépendance de la mécanique.

---

## 6. Vérification manuelle (recette de test)

1. Serveur de dev, 4 joueurs, SVC installé, 2 équipes de 2.
2. **En lobby** : les 4 joueurs se dispersent à 200 blocs. Tous doivent s'entendre → groupe global OK.
3. `/bingo start normal`. **Pendant les 3 s d'animation** : tous s'entendent encore → pas de coupure prématurée.
4. **Après le countdown** : chaque binôme s'entend à 200 blocs de distance ; les équipes adverses ne s'entendent **pas** à 200 blocs.
5. Rapprocher deux joueurs d'équipes adverses à 5 blocs : ils doivent s'entendre **dans les deux sens** → `OPEN` confirmé (avec `NORMAL`, une seule direction passe).
6. `/bingo pause` : les 4 se réentendent à 200 blocs.
7. Déconnecter/reconnecter un joueur en manche : il retrouve le vocal de son binôme.

L'étape 5 est le test qui distingue une intégration correcte d'une intégration approximative.

---

## Sources

- [Simple Voice Chat — Examples (groupes, `Group.Type`)](https://modrepo.de/minecraft/voicechat/api/examples)
- [Simple Voice Chat — Your Plugin (`VoicechatPlugin`, `getPluginId`)](https://modrepo.de/minecraft/voicechat/api/your_plugin)
- [Simple Voice Chat — Javadocs](https://voicechat.modrepo.de/)
- [henkelmax/simple-voice-chat — GitHub](https://github.com/henkelmax/simple-voice-chat)
