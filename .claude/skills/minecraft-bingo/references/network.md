# Réseau et synchronisation

Spec d'origine : `docs/06` §3 et §4.

## La contrainte 1.20.1

`CustomPayload` n'existe pas en 1.20.1. Un paquet est un `Identifier` de canal plus un
`PacketByteBuf` écrit et lu **à la main**. Pas de codec, pas de registre de payloads : c'est pourquoi
chaque record de `network/payload/` porte ses propres `write(buf)` et `read(buf)` statiques.

## Catalogue des canaux

Déclarés dans `src/main/java/com/bingo/mod/network/BingoNetworking.java` — jamais en littéral
ailleurs.

### Serveur → client

| Canal | Payload | Émis quand |
|---|---|---|
| `objective_sync` | `ObjectiveSyncPayload` | connexion, `request_sync`, rechargement de datapack |
| `board_sync` | `BoardSyncPayload` | tirage, reset, connexion, `request_sync` |
| `phase` | `PhasePayload` | chaque transition de phase |
| `tile_update` | `TileUpdatePayload` | une case avance ou se valide |
| `score_update` | `ScoreUpdatePayload` | après chaque `tile_update` |
| `team_sync` | `TeamSyncPayload` | toute mutation de `/bingo team` |
| `player_stats` | `PlayerStatsPayload` | connexion, case créditée, `/bingo points reset` |
| `game_end` | `GameEndPayload` | fin de manche |
| `open_board` | *(vide)* | `/bingo card` — ciblé sur l'émetteur seul |
| `roll_start` | `RollStartPayload` | départ de l'animation, **après** `board_sync` |

### Client → serveur

`request_sync`, charge utile vide, et **c'est le seul**. Rejoindre une équipe, démarrer, mettre en
pause : tout passe par les commandes Brigadier, qui sont déjà un canal validé, permissionné et
journalisé. Réimplémenter ça en paquet custom dupliquerait la validation de permission.

## Les trois règles qui cassent tout si on les oublie

1. **Ordre d'envoi** : `objective_sync` avant `board_sync` (garde-fou 1 de `docs/06` §3.4) — un
   `board_sync` reçu sans son catalogue n'a rien à afficher. Et `roll_start` après `board_sync`, sinon
   l'animation démarre sur une carte que le HUD ne connaît pas.
2. **Un buffer neuf par destinataire.** `CustomPayloadS2CPacket#write` recopie le buffer avec
   `writeBytes(ByteBuf)`, ce qui **avance l'index de lecture de la source** : réutiliser un buffer pour
   huit joueurs envoie le bon paquet au premier et des paquets vides aux sept autres. C'est ce que fait
   `BingoServerNetworking.broadcast` — encoder par destinataire, mais construire la *projection* une
   seule fois hors de la boucle.
3. **Décoder dans le handler réseau**, puis `client.execute(...)`. Le helper
   `BingoClientNetworking.receive(channel, reader, action)` existe pour que la règle ne dépende pas de
   la discipline du prochain auteur : la seule façon d'enregistrer un récepteur décode au bon endroit.

## Bornes d'allocation

Toute taille lue du réseau qui dimensionne une allocation est plafonnée : `BingoBoard.TILE_COUNT`
(25 cases), `BoardSyncPayload.MAX_TEAMS`, `TeamSnapshot.MAX_MEMBERS` (512),
`PlayerStatsPayload.MAX_PLAYERS` (512) et `MAX_NAME_LENGTH` (32, appliqué à l'écriture *aussi*, faute
de quoi un nom trop long ferait lever le client sur son thread réseau — donc déconnecter le joueur
avec un message qui ne désigne rien).

Un identifiant illisible se remplace par un repli (`new TeamId("?")`, `Formatting.WHITE`) plutôt que
de lever : une exception sur le thread réseau déconnecte, un repli laisse le joueur en jeu avec un
affichage dégradé et une ligne de log.

Côté émission, `BingoServerNetworking.send` refuse un paquet au-delà de
`BingoNetworking.MAX_PAYLOAD_SIZE` (1 Mio) : Minecraft ne vérifie pas ce plafond à l'écriture, c'est
le client qui lève à la lecture.

## Ajouter un paquet S2C — les 5 sites

1. `network/BingoNetworking.java` — la constante de canal, avec un javadoc disant *quand* il part.
2. `network/payload/MonPayload.java` — un `record` avec `write(PacketByteBuf)` et
   `static read(PacketByteBuf)`, plus un `of(...)` qui le construit depuis l'état serveur.
3. `BingoGame` — une méthode de projection (`monPayload()`), à côté de `boardSync()` / `teamSync()`.
4. `network/handler/BingoServerNetworking.java` — `sendMonTruc(player, game)` et/ou
   `broadcastMonTruc(game)`, et l'ajouter au handler `JOIN` et à la réponse à `request_sync` si le
   nouvel état doit exister dès l'entrée en jeu.
5. `client/network/BingoClientNetworking.java` — un `receive(...)` vers une méthode `onXxx` de
   `BingoClientState`, qui range les données et rien de plus.

Ne pas oublier `BingoClientState.clear()` : sans nettoyage à la déconnexion, l'état du monde précédent
survit au menu principal.

## Diagnostiquer une désync

Le client demande une resynchronisation (`requestResync()`) dans trois cas déjà câblés : révision de
catalogue divergente, `tile_update` pour une équipe inconnue, ouverture de l'écran de carte. Les deux
côtés s'étranglent (2 s client, 1 s serveur) — un client qui boucle ne fait donc pas travailler le
serveur.

La révision (`BingoData.revision()`) est l'entier qui ferme le trou : un écart entre la révision de la
carte et celle du catalogue signifie que les 25 identifiants ne désignent pas les mêmes objectifs.
