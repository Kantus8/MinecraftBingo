# Minecraft Bingo — installation dans un modpack CurseForge

Version du mod : **0.1.0** — Minecraft **1.20.1** — chargeur **Fabric**

---

## 1. Ce qu'il faut savoir avant

Le mod est un **mod Fabric pour Minecraft 1.20.1**. Il ne fonctionne ni sur Forge, ni sur NeoForge,
ni sur une autre version de Minecraft. Le profil du modpack CurseForge doit donc être créé en
**Fabric 1.20.1**.

⚠️ Le fichier `.jar` se met **directement** dans `mods/`, pas dans un sous-dossier :
Fabric ne scanne pas les sous-dossiers de `mods/`.

---

## 2. Les 3 mods obligatoires

Le mod déclare des dépendances **dures** : s'il en manque une, Fabric Loader refuse de lancer le
jeu et affiche un écran « missing mod ». Ce n'est pas un bug, c'est voulu.

| Mod | Version minimale | Lien CurseForge |
|---|---|---|
| **Fabric API** | 0.92.9+1.20.1 | https://www.curseforge.com/minecraft/mc-mods/fabric-api |
| **Simple Voice Chat** | 1.20.1-2.5.0 | https://www.curseforge.com/minecraft/mc-mods/simple-voice-chat |
| **Just Enough Items (JEI)** | 15.20.0.0 | https://www.curseforge.com/minecraft/mc-mods/jei |

Pense à choisir les fichiers **Fabric / 1.20.1** sur CurseForge — chaque mod publie aussi des
versions Forge et pour d'autres versions de Minecraft.

---

## 3. Marche à suivre (CurseForge)

1. Dans l'app CurseForge : **Create Custom Profile** → Minecraft **1.20.1** → Mod Loader **Fabric**.
2. Onglet **Add More Content** : installe **Fabric API**, **Simple Voice Chat** et **JEI**.
3. Bouton **⋯ → Open Folder** sur le profil : ça ouvre le dossier de l'instance.
4. Copie `bingo-0.1.0+mc1.20.1.jar` (fourni dans ce dossier) dans le sous-dossier **`mods/`**.
5. **Play**.

Le dossier `mods/` doit ressembler à ça :

```
mods/
├── bingo-0.1.0+mc1.20.1.jar
├── fabric-api-0.92.9+1.20.1.jar
├── jei-1.20.1-fabric-15.21.0.148.jar
└── voicechat-fabric-1.20.1-2.5.36.jar
```

---

## 4. Multijoueur

Le mod tourne côté **client et serveur**. Pour jouer à plusieurs :

- **Chaque joueur** doit avoir les 4 mods (Bingo + les 3 dépendances), en versions identiques.
- **Le serveur** doit avoir les 4 mods lui aussi. Un serveur Fabric 1.20.1 suffit — même jar,
  même dossier `mods/`.
- Simple Voice Chat a besoin que son **port vocal soit ouvert** sur le serveur (UDP 24454 par
  défaut). Sans ça, le jeu marche mais le vocal ne se connecte pas. Voir la doc de Simple Voice Chat.

En solo / LAN, le client fait office de serveur : rien de plus à faire.

---

## 5. Vérifier que c'est chargé

En jeu :

- Le HUD 5×5 apparaît une fois une manche lancée.
- Touche **`B`** : ouvre l'écran de carte.
- Commande **`/bingo status`** : répond avec la phase en cours.

Pour lancer une partie il faut être **opérateur** (niveau 2) : créer au moins 2 équipes, puis
`/bingo start normal`. Le détail des commandes, des règles et des réglages est dans le
**guide du joueur** (`README-JOUEUR.md` à la racine du dépôt).

---

## 6. Si ça ne démarre pas

| Symptôme | Cause |
|---|---|
| Écran « incompatible mods / missing mod » au lancement | Une des 3 dépendances manque, ou est en version trop ancienne / mauvais loader |
| « requires version 1.20.1 of minecraft » | Le profil n'est pas en 1.20.1 |
| Le mod n'apparaît pas dans la liste des mods | Le jar est dans un sous-dossier de `mods/`, ou le profil est en Forge |
| Clic gauche sur une case ne fait rien | JEI absent, ou l'objectif n'a pas de recette |

Le log utile est `logs/latest.log` dans le dossier de l'instance : la ligne
`Loading 4 mods` (ou plus) en début de fichier confirme que `bingo` est bien vu par Fabric.
