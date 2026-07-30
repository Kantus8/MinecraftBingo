"""Génère les textures GUI du mod Bingo (docs/03 §6, tâche 4.10).

    python tools/gen_textures.py src/main/resources/assets/bingo/textures

Volontairement géométrique : ce sont des sprites d'interface, pas de l'illustration.
La palette reprend les couleurs qui étaient dans BingoBoardLayout au lot 2, pour que le
passage du rendu en fill() au rendu texturé n'ait rien changé à l'écran.

Le script est versionné parce que les PNG sont *générés* : sans lui, retoucher une bordure
d'un pixel voudrait dire rouvrir un binaire à la main. Regénérer est idempotent.

Dépendance : Pillow (`pip install Pillow`).
"""
from PIL import Image, ImageDraw
import os, sys

OUT = sys.argv[1]

PANEL_BG      = (0, 0, 0, 176)        # 0xB0000000
PANEL_EDGE    = (92, 92, 100, 235)
PANEL_INNER   = (255, 255, 255, 22)
CELL_BG       = (0, 0, 0, 192)        # 0xC0000000
BORDER        = (85, 85, 85, 255)     # 0xFF555555
BORDER_HOVER  = (255, 255, 255, 255)
BORDER_GOLD   = (255, 221, 0, 255)    # 0xFFFFDD00
GOLD_GLOW     = (255, 221, 0, 70)
DONE_VEIL     = (34, 204, 68, 128)    # 0x8022CC44
DONE_BORDER   = (60, 170, 85, 255)


def panel(path, sheet=256, region=64):
    """Feuille 256x256 dont le coin haut-gauche porte un panneau encadré de `region` px.

    256x256 n'est pas un caprice : DrawContext#drawNineSlicedTexture délègue à la surcharge
    de drawTexture qui suppose une texture de 256x256, donc les UV seraient faux sur une
    feuille plus petite. Le reste de la feuille est transparent et ne pèse rien.
    """
    img = Image.new("RGBA", (sheet, sheet), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    last = region - 1
    d.rectangle([0, 0, last, last], fill=PANEL_BG)
    # Bord extérieur 1 px : ce qui détache le panneau d'un fond clair.
    d.rectangle([0, 0, last, last], outline=PANEL_EDGE)
    # Liseré intérieur haut/gauche : un biseau d'un pixel suffit à donner du relief.
    d.line([(1, 1), (last - 1, 1)], fill=PANEL_INNER)
    d.line([(1, 1), (1, last - 1)], fill=PANEL_INNER)
    img.save(path)


def cell_sprite(d, ox, oy, s, bg, border, veil=None, glow=None):
    d.rectangle([ox, oy, ox + s - 1, oy + s - 1], fill=bg)
    if veil:
        d.rectangle([ox + 1, oy + 1, ox + s - 2, oy + s - 2], fill=veil)
    if glow:
        d.rectangle([ox + 1, oy + 1, ox + s - 2, oy + s - 2], outline=glow)
    d.rectangle([ox, oy, ox + s - 1, oy + s - 1], outline=border)


def cells(path, s=18, size=64):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cell_sprite(d, 0, 0, s, CELL_BG, BORDER)                              # normale
    cell_sprite(d, s, 0, s, CELL_BG, BORDER_HOVER)                        # survolée
    cell_sprite(d, 0, s, s, CELL_BG, DONE_BORDER)                         # validée
    cell_sprite(d, s, s, s, CELL_BG, BORDER_GOLD, glow=GOLD_GLOW)         # dorée
    img.save(path)


def check(path, size=8):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    w = (255, 255, 255, 255)
    # Branche courte descendante puis branche longue montante, en escalier 2 px.
    for i in range(3):
        d.rectangle([i, 2 + i, i + 1, 3 + i], fill=w)
    for i in range(5):
        d.rectangle([2 + i, 4 - i, 3 + i, 5 - i], fill=w)
    img.save(path)


os.makedirs(os.path.join(OUT, "gui", "hud"), exist_ok=True)
panel(os.path.join(OUT, "gui", "hud", "panel.png"))
cells(os.path.join(OUT, "gui", "hud", "cell.png"))
check(os.path.join(OUT, "gui", "hud", "check.png"))
print("ok")
