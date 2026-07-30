# 03 — Spécification HUD 5×5 et intégration JEI

---

## ⚠️ Contrainte technique à connaître avant de lire la suite

**Un overlay HUD Minecraft ne reçoit jamais d'événement de clic.** Les clics ne sont routés que vers un `Screen` actif. Un HUD dessiné via `HudRenderCallback` est, par construction, non cliquable.

La demande « clic sur la grille du HUD » se résout donc en **deux composants complémentaires** :

| Composant | Rôle | Cliquable |
|---|---|---|
`BingoHudOverlay` | Affichage permanent en jeu, coin haut-gauche | non |
`BingoBoardScreen` | Écran ouvert par touche (`B`), **rendu au même endroit et à la même échelle** | oui |

L'écran redessine la grille **exactement à la position et à la taille du HUD**, sans assombrir l'arrière-plan. Résultat perçu par le joueur : il appuie sur `B` et « le HUD devient cliquable ». C'est la seule façon propre d'obtenir ce comportement, et l'illusion est parfaite si les deux rendus partagent le même code de layout.

> **Règle d'implémentation** : le calcul de layout vit dans **une seule** classe, `BingoBoardLayout`, consommée par le HUD et par l'écran. Zéro duplication de constantes — sinon le HUD et l'écran finiront désalignés d'un pixel et l'illusion tombe.

---

## 1. Layout — valeurs pixel

Toutes les valeurs sont en pixels d'interface (après `guiScale`), et dérivées de `BingoBoardLayout`.

```
ORIGINE : coin haut-gauche de l'écran
  MARGIN_X = 8        MARGIN_Y = 8

┌─────────────────────────────────────┐  ← x=8, y=8
│  BINGO            12:34             │  BARRE DE TITRE   h = 12
├─────────────────────────────────────┤
│  ┌──┐┌──┐┌──┐┌──┐┌──┐               │
│  │  ││  ││  ││  ││  │               │  CELL = 18 × 18
│  └──┘└──┘└──┘└──┘└──┘               │  GAP  = 2
│  ┌──┐┌──┐┌──┐┌──┐┌──┐               │  PITCH = 20
│  │  ││  ││  ││  ││  │               │
│  └──┘└──┘└──┘└──┘└──┘               │  GRID  = 5×20 − 2 = 98
│         … 5 lignes …                │
├─────────────────────────────────────┤
│  ■ Rouge   8  · ■ Bleu   6          │  PIED DE SCORE   h = 10
└─────────────────────────────────────┘
   PADDING = 4 de chaque côté

LARGEUR TOTALE = 4 + 98 + 4          = 106
HAUTEUR TOTALE = 4 + 12 + 98 + 10 + 4 = 128
```

### Constantes

| Nom | Valeur | Note |
|---|---|---|
| `MARGIN_X`, `MARGIN_Y` | 8, 8 | configurable, défaut coin haut-gauche |
| `PADDING` | 4 | |
| `CELL_SIZE` | 18 | = taille d'un slot vanilla, l'item 16×16 est centré |
| `CELL_GAP` | 2 | |
| `CELL_PITCH` | 20 | `CELL_SIZE + CELL_GAP` |
| `GRID_SIZE` | 98 | `5 × 20 − 2` |
| `TITLE_H` | 12 | |
| `FOOTER_H` | 10 | 0 si `reveal_opponent_progress: false` |
| `PANEL_W` | 106 | |
| `PANEL_H` | 128 | **118 si `reveal_opponent_progress: false`** (le pied disparaît) |
| `hudScale` | 1.0 | configurable 0.75 → 1.5, appliqué en `matrices.scale()` |

**Index de case** : `index = row * 5 + col`, `row` et `col` de 0 à 4. Cet ordre est **normatif** — le réseau, les datapacks, les commandes de debug et le rendu l'utilisent tous.

**Hit-test** (dans `BingoBoardScreen`) :

```java
int localX = mouseX - originX - PADDING;
int localY = mouseY - originY - PADDING - TITLE_H;
int col = localX / CELL_PITCH;
int row = localY / CELL_PITCH;
// rejeter si hors [0,5[ ou si le reste tombe dans le GAP
boolean inCell = (localX % CELL_PITCH) < CELL_SIZE
              && (localY % CELL_PITCH) < CELL_SIZE
              && col >= 0 && col < 5 && row >= 0 && row < 5;
```

Ne pas oublier de diviser `mouseX/mouseY` par `hudScale` avant le hit-test si l'échelle est appliquée.

---

## 2. États visuels d'une case

Rendu par couches, du fond vers l'avant :

| Couche | Contenu |
|---|---|
| 1 | Fond de case : `0xC0000000` (noir 75 %) |
| 2 | Bordure 1 px : `0xFF555555` normal, `0xFFFFFFFF` si survolée |
| 3 | Icône : `DrawContext#drawItem` de `display.icon`, 16×16 centré |
| 4 | Voile d'état (voir table) |
| 5 | Pastilles adversaires : carrés 3×3 aux coins, couleur de l'équipe |
| 6 | Badge de compte : `count` restant, en bas-droite, si `count > 1` |

| État | Voile | Symbole |
|---|---|---|
| `PENDING` | aucun | — |
| `DONE_BY_MY_TEAM` | `0x8022CC44` (vert) | coche blanche 8×8 |
| `DONE_BY_OTHER_ONLY` | aucun | pastilles de coin uniquement |
| `IN_PROGRESS` (`count` partiel) | aucun | badge `3/8` en bas-droite |
| `HIGHLIGHT_WINNING_LINE` | pulsation `0x40FFDD00` | bordure dorée |

**Position des pastilles adversaires** : au plus 3 équipes adverses (`max_teams` = 4), placées aux coins haut-gauche, haut-droit et bas-gauche — le bas-droit est réservé au badge de compte. Les trois emplacements suffisent exactement : aucun cas de débordement à gérer tant que `max_teams` vaut 4.

**Mise en avant des lignes** : quand une équipe est à **une case** de compléter une ligne, colonne ou diagonale, les 4 cases déjà validées de cette ligne prennent la bordure dorée. Donne l'information « je suis proche » sans texte.

---

## 3. Routage du clic

```
clic gauche sur la case i
        │
        ├─ objectif = tiles[i]
        ├─ interaction = objectif.interaction ?? défaut(objectif.type)
        │
        ├─ "jei"     ─────►  openInJei(objectif)
        ├─ "tooltip" ─────►  afficher le pop-up de description (§3.3)
        └─ "none"    ─────►  aucune action, jouer ui.button.click à volume 0.3
```

Le **clic droit** affiche toujours le tooltip, quel que soit le type. Utile pour lire la description d'un `CRAFT` sans quitter l'écran.

### 3.1 `openInJei()` — types `CRAFT` et `FIND`

```java
// com.bingo.mod.client.integration.jei.BingoJeiBridge
public static boolean showRecipe(Objective objective) {
    IJeiRuntime runtime = BingoJeiPlugin.getRuntime();
    if (runtime == null) return false;                 // JEI pas encore initialisé

    ItemStack stack = objective.display().iconStack();
    if (stack.isEmpty()) return false;

    RecipeIngredientRole role = objective.jeiRole() == JeiRole.INPUT
            ? RecipeIngredientRole.INPUT
            : RecipeIngredientRole.OUTPUT;

    IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
    IFocus<ItemStack> focus = focusFactory.createFocus(role, VanillaTypes.ITEM_STACK, stack);

    runtime.getRecipesGui().show(focus);               // remplace l'écran courant
    return true;
}
```

- `RecipeIngredientRole.OUTPUT` = « comment obtenir cet item » → c'est ce qu'on veut pour `CRAFT` **et** `FIND`.
- `runtime.getRecipesGui().show(focus)` ouvre le `RecipesGui` de JEI, qui **remplace** `BingoBoardScreen`. Ne pas appeler `client.setScreen(null)` avant : JEI gère la transition et son bouton retour ramènera à l'écran précédent.
- **Si `show()` ne trouve aucune recette**, JEI ne fait rien de visible et l'écran ne change pas. C'est indistinguable d'un clic ignoré. **Fallback obligatoire** : mémoriser l'écran courant, et si après l'appel `client.currentScreen` est inchangé, afficher le tooltip à la place.

```java
Screen before = client.currentScreen;
boolean sent = BingoJeiBridge.showRecipe(objective);
if (!sent || client.currentScreen == before) {
    showTooltipPopup(objective);       // dégradation propre
}
```

Ce fallback est ce qui évite un « clic mort » sur `FIND minecraft:ancient_debris`, qui n'a aucune recette.

### 3.2 Enregistrement du plugin JEI

```java
// src/main/java/com/bingo/mod/integration/jei/BingoJeiPlugin.java
@JeiPlugin
public class BingoJeiPlugin implements IModPlugin {

    private static @Nullable IJeiRuntime runtime;

    @Override
    public Identifier getPluginUid() {
        return new Identifier("bingo", "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;                 // NE PAS OUBLIER — sinon fuite entre deux mondes
    }

    public static @Nullable IJeiRuntime getRuntime() { return runtime; }
}
```

> ⚠️ **Sur Fabric, l'annotation ne suffit pas.** Le scan d'annotations est le mécanisme de la version Forge. Sur Fabric, `mezz.jei.fabric.startup.FabricPluginFinder` lit l'entrypoint **`jei_mod_plugin`** de `fabric.mod.json` — vérifié dans les sources de `jei-1.20.1-fabric:15.21.0.148`, où JEI déclare ses propres plugins de cette façon. Sans cet entrypoint, le plugin n'est **jamais instancié** et JEI ne dit rien : aucun avertissement, aucune erreur, juste des clics sans effet.
>
> ```json
> "entrypoints": {
>   "jei_mod_plugin": [ "com.bingo.mod.integration.jei.BingoJeiPlugin" ]
> }
> ```
>
> L'annotation `@JeiPlugin` est conservée : l'API en fait un contrat (`IModPlugin` doit la porter et avoir un constructeur sans argument), mais c'est l'entrypoint qui charge la classe.

La classe vit dans `src/main/java` (voir `docs/06` §5 pour la raison). L'entrypoint `jei_mod_plugin` n'est interrogé que par le client : sur un serveur dédié, la classe n'est jamais chargée.

### 3.3 Pop-up de description — types `KILL_MOB`, `DEATH`, `ACTION`

Un tooltip vanilla (`Screen#renderTooltip`) suivant le curseur, composé de :

```
┌──────────────────────────────────────┐
│ Tuer un Blaze                    ×3  │  ← display.name  +  badge count
│                                      │
│ Les Blazes apparaissent dans les     │  ← display.description
│ forteresses du Nether.               │     (wrap à 200 px)
│                                      │
│ Niveau 3 · 400 pts                   │  ← gris,  points_base × 2^(level-1)
│ Validé par : ■ Rouge                 │  ← si des équipes ont validé
└──────────────────────────────────────┘
```

Sur le HUD non cliquable, le même contenu est accessible en survol **uniquement** lorsque `BingoBoardScreen` est ouvert. Pas de tooltip en jeu libre : le curseur est verrouillé.

---

## 4. Comportement du HUD en jeu

| Condition | Affichage |
|---|---|
Pas de partie (`LOBBY`, aucune carte) | HUD masqué |
`ROLLING` | HUD visible, cases en animation (voir `docs/04`) |
`RUNNING`, `COUNTDOWN`, `PAUSED` | HUD complet |
`FINISHED` | HUD visible 10 s avec la ligne gagnante en surbrillance, puis masqué |
Joueur en spectateur | HUD complet, pied de score de toutes les équipes |
`F1` (HUD caché) | HUD masqué (respecter `client.options.hudHidden`) |
Écran de chat / inventaire ouvert | HUD **masqué** (évite le chevauchement avec la liste des effets) |
Touche `Toggle HUD` | Bascule manuelle, persistée dans la config client |

**Conflit connu** : les effets de potion s'affichent en haut-**droite**, pas de collision. En revanche `MARGIN_Y = 8` chevauche le titre de boss bar sous certaines resource packs — laisser `MARGIN_Y` configurable et documenter.

---

## 5. Keybinds

| Action | Défaut | Catégorie |
|---|---|---|
Ouvrir la grille cliquable | `B` | `key.categories.bingo` |
Afficher/masquer le HUD | non assigné | `key.categories.bingo` |
Parler à l'équipe | non assigné | géré par Simple Voice Chat, pas par nous |

`B` est libre en vanilla 1.20.1. La ligne « parler à l'équipe » est mentionnée pour mémoire : SVC gère déjà ses propres binds de groupe, on ne les duplique pas.

---

## 6. Textures à produire

| Fichier | Taille | Contenu |
|---|---|---|
`textures/gui/hud/panel.png` | 128×128 | Fond du panneau, 9-slice |
`textures/gui/hud/cell.png` | 64×32 | Atlas : case normale · survolée · validée · dorée (4 × 18×18) |
`textures/gui/hud/check.png` | 8×8 | Coche de validation |
`textures/gui/icons/level_1..5.png` | 8×8 | Pastilles de niveau (optionnel, lot 4) |

> ✅ **Livrées au lot 4**, avec deux écarts par rapport au tableau :
>
> - `panel.png` fait **256×256** et non 128×128, la région utile étant les 64×64 du coin haut-gauche. Ce n'est pas un choix esthétique : `DrawContext#drawNineSlicedTexture` délègue à la surcharge de `drawTexture` qui **suppose une feuille de 256×256**, et les UV seraient faux sur une feuille plus petite. Le reste est transparent.
> - `cell.png` fait **64×64** et non 64×32 : quatre sprites de 18×18 ne tiennent pas en 64×32 (4 × 18 = 72 en largeur, 18 en hauteur — le tableau d'origine se contredit). Ils sont disposés en 2×2, dans l'ordre normale · survolée · validée · dorée.
>
> Les PNG sont **générés** par [`tools/gen_textures.py`](../tools/gen_textures.py) plutôt que dessinés : ce sont des sprites géométriques, et un générateur versionné se retouche là où un binaire ne se retouche pas. Ce qui reste en `fill()` y reste pour une raison — voiles translucides posés *par-dessus* l'icône, pastilles à la couleur d'équipe, flash de verrouillage, étincelles : autant de couleurs décidées à l'exécution, qu'aucun sprite ne peut porter.

---

## Sources

- [JEI — dépôt et API (`IJeiRuntime`, `IFocusFactory`)](https://github.com/mezz/JustEnoughItems)
- [JEI 1.20.1 Fabric API sur maven.blamejared.com](https://maven.blamejared.com/mezz/jei/jei-1.20.1-fabric-api/)
