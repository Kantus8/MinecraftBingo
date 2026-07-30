# Boucle de jeu, équipes, scoring et persistance

Spec d'origine : `docs/05` (règles, scoring, commandes) et `docs/06` (§1 machine à états, §2 modèle de
données, §6 chemin critique de validation).

## Cycle de vie de l'état

`BingoGame` est attaché au `MinecraftServer` courant — `of(server)` au démarrage, `detach(server)` à
l'arrêt — et non un vrai singleton statique : ça évite l'état résiduel entre deux mondes solo ouverts
dans la même session.

`attachPersistence()` doit être appelé sur `SERVER_STARTED` et pas `SERVER_STARTING` : au second, les
mondes n'existent pas et les datapacks ne sont pas chargés, donc aucun identifiant de case ne serait
résoluble et toute partie sauvegardée basculerait en `FINISHED`.

## Machine à phases

`LOBBY → ROLLING → COUNTDOWN → RUNNING ⇄ PAUSED → FINISHED`, et `LOBBY` est atteignable depuis
**toutes** les phases (`/bingo reset` est le filet de sécurité). La table `ALLOWED_TRANSITIONS` est
l'autorité : une transition refusée est journalisée en ERROR, jamais silencieuse, parce que c'est
toujours un bug d'appelant.

`GamePhase` porte les prédicats que le reste du code interroge (`isRoundActive`,
`areObjectivesValidated`, `isTimerTicking`) — les tester par comparaison d'enum ailleurs dupliquerait
la table des phases.

Les seules échéances de la partie vivent dans `tick()` : sortie de `ROLLING`, décompte, temps écoulé,
et les deux scans périodiques (inventaires toutes les 10 ticks, positions toutes les 20).

## Chemin critique de validation (`docs/06` §6)

```
événement de jeu
  1. phase valide ?                          sinon → abandon
  2. le joueur a-t-il une équipe ?           sinon → abandon
  3. parcourir les cases NON validées du bon type   ← point chaud
  4. l'objectif matche-t-il l'événement ?
  5..8 → BingoGame.applyProgress : avancement, victoire, paquets, annonces
```

Les étapes 1-4 sont dans `game/detect/ObjectiveValidator.java` (`gate(player)` fait 1 et 2), les
étapes 5-8 dans `BingoGame` — et nulle part ailleurs.

L'étape 3 ne parcourt **jamais** les 25 objectifs : elle interroge `TeamPendingIndex`, un index inversé
immuable par équipe (`craftByItem`, `killByEntity`, `findByItem`, `byType`…), reconstruit après chaque
tirage et chaque complétion. Sur le scan `FIND`, deux fois par seconde et par joueur, ce n'est pas
cosmétique.

Le scan d'inventaire accumule dans un `int[25]` primitif — une seule passe sur les 41 emplacements,
sans autoboxing ni hachage — et **l'avancement affiché fonctionne à cliquet** : il ne redescend jamais,
sinon ramasser et jeter de la terre émettrait un `tile_update` à chaque fluctuation.

`applyProgress` prend un `newProgress` **absolu**, jamais un delta : le scan `FIND` recompte
l'inventaire à chaque passage et n'a pas de delta à offrir.

## Scoring

`game/BingoScoring.java`, 117 lignes — le lire en entier coûte moins que de deviner.

- Score d'une case : `pointsBase << (level − 1)`. Décalage de bits et non `Math.pow`, pour qu'un score
  entier reste entier sans arrondi possible.
- Score d'équipe : somme des cases validées, **recalculée de zéro** à chaque appel. Un score accumulé
  se désynchroniserait à la première annulation ou au premier rechargement.
- Classement : score, puis nombre de cases, puis meilleure combinaison entamée (« 4/5 bat 3/5 »), puis
  un départage stable par `TeamId` — sans lui, l'ordre d'affichage d'un match nul dépendrait de l'ordre
  d'itération.
- Le match nul n'est pas un critère de tri mais une lecture du résultat : `isDraw(ranking)`.

## Points individuels

`game/PlayerPoints.java` — **le seul accumulateur du mod**, et il l'est par nécessité : un total qui
traverse les manches n'a rien dont se dériver (la carte précédente n'existe plus, `/bingo reset` détruit
les équipes).

- Crédité à la **complétion** seulement, au joueur passé en `contributor` d'`applyProgress`. Les chemins
  sans auteur (rechargement de datapack, `/bingo debug complete`) passent `null` : créditer un joueur au
  hasard serait pire qu'un total incomplet.
- Le dernier pseudo connu est mémorisé avec le total, sinon le tableau afficherait des UUID pour tout
  membre déconnecté — et un membre déconnecté reste dans son équipe.
- Survit à `stop`, `reroll`, `reset` et au redémarrage. `/bingo points reset` est la **seule** remise à
  zéro. Corollaire assumé : `/bingo debug uncomplete` ne rend pas les points déjà crédités.

## Équipes

`game/team/` : `TeamManager` (composition, invariant « au plus une équipe par joueur » — `join` retire
donc toujours de la précédente), `BingoTeam` (identité + complétion), `TeamId`, `TeamPendingIndex`.

Ce que `BingoTeam` ne contient **pas** est aussi important : pas de score, pas de compteur de cases,
pas de drapeau « a gagné ». Tout se dérive de `completionMask` (25 bits) et des 25 horodatages, qui
arbitrent les égalités.

Durées de vie à ne pas confondre :

| Commande | Effet sur les équipes |
|---|---|
| `/bingo stop` | conservées, avec leur composition et leur grille |
| `/bingo reroll` | conservées ; grilles remises à zéro (`clearCompletions`) |
| `/bingo team clear` | vidées de leurs membres, non supprimées (`clearMembers`) |
| `/bingo reset` | supprimées (`removeAll`) |

`autobalance` complète d'abord les équipes incomplètes avant d'en ouvrir une nouvelle : un binôme
orphelin doit être comblé avant qu'une équipe de plus n'apparaisse.

## Spectateurs

Les joueurs sans équipe passent spectateurs au départ de la manche, et **seuls ceux-là** sont mémorisés
dans `forcedSpectators` : c'est ce que `reset()` ramènera au mode par défaut. Rendre son mode à tout
spectateur trouvé remettrait en jeu un modérateur qui observait de son plein gré.

## Persistance

`world/BingoPersistentState.java` branche le mécanisme vanilla sur `BingoGame.writeNbt/readNbt` et ne
contient **aucune donnée** — dupliquer les champs obligerait à les recopier dans les deux sens à chaque
mutation. Fichier : `<monde>/data/bingo.dat`, attaché à l'overworld seul (une partie est unique par
serveur ; un état par dimension se dédoublerait au premier passage au Nether).

Deux corrections à la relecture, faciles à casser en refactorant :

- **Le temps d'arrêt serveur est du temps de pause.** `savedAtMs` est horodaté à l'écriture ; sans cette
  déduction, une heure de serveur éteint compterait comme une heure de jeu et une manche reprise se
  terminerait aussitôt.
- **`ROLLING` et `COUNTDOWN` sont réarmés** au démarrage : leur échéance est transitoire, les relire
  telles quelles laisserait la partie figée pour toujours.

Dégradation exigée : si une case référence un objectif disparu du datapack, la manche bascule en
`FINISHED` avec un WARN. Ni crash, ni case fantôme. Même logique à chaud dans `onDataReload()`, qui
sinon réaligne les 25 cases sur les définitions fraîches et réapplique l'avancement contre le nouveau
`count`.

`markDirty()` après **chaque** mutation persistée, sinon la sauvegarde ne part pas.

## Détection d'événements

`game/detect/BingoDetectors.java` enregistre les events Fabric (mort d'entité, réveil, changement de
dimension, clic sur bloc). Ce que Fabric n'expose pas passe par un mixin — `src/main/java/com/bingo/mod/mixin/`
en contient 5 : craft (`ItemStackCraftMixin`), avancement, apprivoisement, troc villageois, enchantement.

Crédit d'une mise à mort : l'attaquant de la `DamageSource` d'abord (pour un projectile c'est déjà le
propriétaire, ce qui règle le tir à l'arc), sinon `getPrimeAdversary` — qui expire au bout de 100 ticks,
soit exactement la fenêtre de 5 s voulue pour les chutes et bains de lave provoqués. Une noyade ne
crédite personne, comme demandé.

## Annonces

`game/BingoAnnouncer.java`. Le son « 4/5 » est **local à l'équipe concernée** : c'est l'information la
plus précieuse de la partie, l'annoncer publiquement retirerait tout l'intérêt de lire le HUD adverse.
Et il ne sonne que quand une case vient de *créer* un 4/5, pas à chaque validation ultérieure.
