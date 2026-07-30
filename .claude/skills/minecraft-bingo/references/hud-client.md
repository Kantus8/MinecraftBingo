# HUD, écrans et client

Spec d'origine : `docs/03` (layout, états visuels, clic, keybinds) et `docs/04` (animation de tirage).

## La contrainte à connaître avant tout

**Un overlay dessiné par `HudRenderCallback` ne reçoit jamais la souris.** Minecraft ne route les
événements pointeur que vers un `Screen` actif. Le mod n'essaie donc pas de rendre le HUD cliquable :
`BingoBoardScreen` s'ouvre **sans assombrir l'arrière-plan** et redessine la grille *exactement* au
même endroit, à la même échelle. Le joueur perçoit « le HUD est devenu cliquable ».

L'illusion tient à une seule chose : le rendu et le layout sont partagés entre l'overlay et l'écran.
D'où la séparation layout / renderer, qu'il ne faut pas contourner.

## Les deux panneaux

| | Grille | Tableau des équipes |
|---|---|---|
| Position | haut-gauche | haut-**droite** (abscisse déduite de la largeur de fenêtre) |
| Layout | `hud/BingoBoardLayout.java` | `hud/BingoTeamPanelLayout.java` |
| Rendu | `hud/BingoBoardRenderer.java` | `hud/BingoTeamPanelRenderer.java` |
| Largeur | fixe, 106 px (`2×PADDING + GRID_SIZE`) | variable, mesurée sur le contenu, bornée 84–150 px |
| Visible si | une carte est tirée | toujours, salon compris |
| Gate | `BingoClientState.shouldRenderHud` | `BingoClientState.shouldRenderTeamPanel` |

Les deux sont enregistrés dans un **unique** `HudRenderCallback` (`hud/BingoHudOverlay.java`), mais
avec des conditions d'affichage distinctes — d'où deux méthodes plutôt que deux `return` dans la même.

Les deux se masquent dès qu'un écran s'ouvre (Minecraft dessine le HUD *sous* les écrans, donc les
laisser visibles les ferait chevaucher l'inventaire). `BingoBoardScreen` les redessine tous les deux
lui-même : c'est le seul écran où l'affichage doit survivre.

## Conventions de layout

Les valeurs sont **dérivées**, pas recopiées depuis `docs/03` : `GRID_SIZE = SIZE × CELL_PITCH − GAP`
est un résultat. Une case fait 18 px — la taille d'un emplacement vanilla, ce qui centre l'item 16×16
sans calcul.

Tout passe par `scale()` (le `hud_scale` de la config client) : rendu, hit-test et test de débordement.
L'échelle est appliquée en `matrices.scale()`, donc **la souris vit dans l'espace non mis à l'échelle** —
`hitTest` divise les coordonnées avant de tester, et l'oublier décale le clic d'autant. Même piège pour
l'ancrage à droite : la largeur de fenêtre doit être divisée par l'échelle, sinon le panneau sort de
l'écran dès que `hud_scale` dépasse 1.

Deux façons de rater un panneau trop grand, et le dépôt a choisi la seconde selon le cas :
- **refuser de dessiner** (`fitsOnScreen`) quand rien ne peut tenir en largeur ;
- **borner le contenu** (`maxRows` + ligne `+N`) quand seule la hauteur manque — un panneau qui
  disparaît parce qu'un opérateur a entassé six joueurs dans une équipe se lit comme un bug.

## Textures

Dans `assets/bingo/textures/gui/hud/` :

| Fichier | Format | Notes |
|---|---|---|
| `panel.png` | 9-slice, région 64, coin 4, **feuille 256×256** | la taille de feuille n'est pas négociable : `drawNineSlicedTexture` la suppose, les UV seraient faux sinon |
| `cell.png` | atlas 2×2 de 18×18 dans 64×64 | 4 états : normal, survolé, validé, doré |
| `check.png` | 8×8 | coche de validation |

`BingoBoardRenderer.drawPanel(...)` est exposé pour que le tableau des équipes partage la texture et
ses métriques — deux panneaux du même HUD au cadre légèrement différent se verraient.

Ce qui reste en `fill()` y reste pour une raison : voile translucide posé **par-dessus** l'icône,
pastille à la couleur d'équipe, flash de verrouillage, étincelles. Autant de couleurs décidées à
l'exécution, qu'aucun sprite ne peut porter.

Les textures étant translucides, `RenderSystem.enableBlend()` + `defaultBlendFunc()` sont **posés** et
non espérés : l'état de mélange du HUD n'est pas garanti au moment où notre callback passe.

## État client

`client/BingoClientState.java` — statique, purement présentationnel. Bandeaux :
« Réception » (un `onXxx` par paquet), « Lecture » (les getters du rendu), « Visibilité du HUD »,
« Visibilité du tableau des équipes », « Resynchronisation ».

Deux détails qui se paient cher si on les ignore :

- **Le chrono est extrapolé** depuis la dernière réception (`syncedAtMs`), jamais resynchronisé par
  tick. Une dérive de quelques centaines de millisecondes est invisible ; 20 paquets/s par joueur ne
  le seraient pas.
- **Un `TeamSnapshot` se remplace, jamais ne se mute.** Le rendu le lit depuis le thread principal
  pendant qu'un paquet arrive sur le thread réseau : remplacer une référence est atomique, muter un
  tableau ne l'est pas. Voir `withTile(...)`.

## Config client et keybinds

`client/config/BingoClientConfig.java` → `config/bingo-client.json`. Volontairement **hors** de
`/bingo config` : ces clés décrivent la fenêtre d'*un* joueur, et les faire transiter par une commande
serveur laisserait un opérateur décider de l'échelle du HUD des autres.

Clés : `hud_margin_x`, `hud_margin_y`, `hud_scale` (borné 0,75–1,5), `hud_visible`,
`team_panel_margin_x`, `team_panel_margin_y` (marges comptées depuis le bord **droit**),
`team_panel_visible`.

`Values` est une classe à champs publics et non un `record` : Gson instancie par réflexion sans appeler
le constructeur, donc un champ absent du JSON garde sa valeur d'initialisation — c'est ce qui rend un
fichier partiel valide et un fichier d'une version antérieure lisible. Un fichier illisible n'est
**pas** écrasé : défauts en mémoire, WARN, fichier conservé.

Keybinds (`client/input/BingoKeybinds.java`) : `B` ouvre la carte ; les deux bascules d'affichage sont
**non assignées** par défaut — un réglage qu'on change une fois ne mérite pas de confisquer une touche.
Boucler sur `wasPressed()` (et non `isPressed()`) : sinon maintenir la touche rejoue l'action chaque tick.

## Animation de tirage

`client/roll/RollAnimationState.java` + `RollSparks.java`, spec `docs/04`.

Un seul paquet (`roll_start`) puis plus rien pendant 3 secondes : chaque client rejoue la séquence
depuis la graine. Tout — icônes, verrous, flash, punch — se calcule au rendu depuis `elapsed`. Seuls
les sons ont besoin d'un pouls, d'où le `ClientTickEvents.END_CLIENT_TICK` dans `BingoModClient`.

L'animation n'est **pas** coupée à l'entrée en `COUNTDOWN` : le serveur y transite à t=3000, soit
l'instant exact du son final. Elle s'éteint sur sa propre durée. Un retour au salon ou une fin de
manche l'interrompt, en revanche.

## JEI

`client/integration/jei/BingoJeiBridge.java` (côté client) et `integration/jei/BingoJeiPlugin.java`.
Dégradation nécessaire : quand un item n'a aucune recette (`ancient_debris` est l'exemple canonique),
`show()` ne fait rien de visible et ne le dit pas. Le seul signal exploitable est que l'écran courant
n'a pas changé — d'où le repli sur le tooltip avec une mention temporaire.
