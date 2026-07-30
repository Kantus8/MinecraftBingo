#!/usr/bin/env python3
"""Génère l'éditeur d'objectifs du bingo — une page HTML autonome.

L'outil ne parle pas au mod : il lit le datapack, produit une page où on ajuste
les niveaux / écrit de nouveaux objectifs, et en ressort un brief à coller dans
Claude Code. L'implémentation reste manuelle, l'outil ne fait que la cadrer.

Usage :
    python tools/objective-editor/build.py            # génère puis affiche le chemin
    python tools/objective-editor/build.py --open     # génère et ouvre le navigateur

Relancer après toute modification du datapack : la page embarque un instantané,
elle ne se met pas à jour toute seule.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import webbrowser
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

DATA_DIR = ROOT / "src/main/resources/data/bingo"
LANG_DIR = ROOT / "src/main/resources/assets/bingo/lang"
OBJECTIVE_JAVA = ROOT / "src/main/java/com/bingo/mod/objective/Objective.java"
TRIGGERS_JAVA = ROOT / "src/main/java/com/bingo/mod/game/detect/ActionTriggers.java"
BOARD_JAVA = ROOT / "src/main/java/com/bingo/mod/board/BingoBoard.java"

TEMPLATE = HERE / "template.html"
OUTPUT = HERE / "objective-editor.html"

PLACEHOLDER = "/*__BINGO_DATA__*/ null"

#: Points d'une case de niveau 1 — sert au score max indicatif des profils.
POINTS_BASE = 100


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def scan_java_int(path: Path, constant: str, fallback: int) -> int:
    """Lit une constante entière côté Java pour éviter de la dupliquer ici."""
    if not path.exists():
        return fallback
    match = re.search(rf"{constant}\s*=\s*(\d+)", path.read_text(encoding="utf-8"))
    return int(match.group(1)) if match else fallback


def scan_triggers() -> list[str]:
    """Les ids de trigger disponibles pour les objectifs `bingo:action`."""
    if not TRIGGERS_JAVA.exists():
        return []
    source = TRIGGERS_JAVA.read_text(encoding="utf-8")
    names = re.findall(r"BingoConstants\.id\(\"([a-z_]+)\"\)", source)
    return sorted({f"bingo:{name}" for name in names})


def lang_maps() -> tuple[dict, dict]:
    def load(name: str) -> dict:
        path = LANG_DIR / name
        return read_json(path) if path.exists() else {}
    return load("fr_fr.json"), load("en_us.json")


def translate_key(node) -> str | None:
    """Extrait la clé d'un champ texte `{"translate": "…"}`."""
    if isinstance(node, dict):
        return node.get("translate")
    return None


def resolve_text(node, fr: dict, en: dict) -> tuple[str, str, str | None]:
    """→ (texte fr, texte en, clé). Un texte littéral n'a pas de clé."""
    key = translate_key(node)
    if key:
        return fr.get(key, ""), en.get(key, ""), key
    if isinstance(node, str):
        return node, node, None
    return "", "", None


def collect_objectives(fr: dict, en: dict) -> list[dict]:
    root = DATA_DIR / "objectives"
    objectives = []

    for path in sorted(root.rglob("*.json")):
        raw = read_json(path)
        relative = path.relative_to(root).with_suffix("").as_posix()
        display = raw.get("display", {})

        name_fr, name_en, name_key = resolve_text(display.get("name"), fr, en)
        desc_fr, desc_en, desc_key = resolve_text(display.get("description"), fr, en)

        objectives.append({
            "id": f"bingo:{relative}",
            "path": relative,
            "file": path.relative_to(ROOT).as_posix(),
            "type": raw["type"],
            "level": raw["level"],
            "weight": raw.get("weight", 10),
            "count": raw.get("count", 1),
            "target": raw.get("target", {}),
            "tags": raw.get("tags", []),
            "conflicts": raw.get("conflicts", []),
            "requires_dimension": raw.get("requires_dimension"),
            "points_base": raw.get("points_base"),
            "announce": raw.get("announce", True),
            "interaction": raw.get("interaction"),
            "jei_role": raw.get("jei_role"),
            "icon": display.get("icon"),
            "icon_count": display.get("icon_count"),
            "name_key": name_key,
            "desc_key": desc_key,
            "name_fr": name_fr,
            "name_en": name_en,
            "desc_fr": desc_fr,
            "desc_en": desc_en,
        })

    return objectives


def collect_profiles(fr: dict, en: dict) -> list[dict]:
    root = DATA_DIR / "difficulties"
    profiles = []

    for path in sorted(root.glob("*.json")):
        raw = read_json(path)
        display_fr, _, display_key = resolve_text(raw.get("display_name"), fr, en)
        profiles.append({
            "name": path.stem,
            "file": path.relative_to(ROOT).as_posix(),
            "display_key": display_key,
            "display_fr": display_fr or path.stem,
            "pool": raw.get("pool"),
            # Les clés JSON sont des chaînes ; on les garde telles quelles, le JS
            # indexe indifféremment dist[3] et dist["3"].
            "distribution": {str(k): v for k, v in raw.get("distribution", {}).items()},
            "time_limit_seconds": raw.get("time_limit_seconds"),
            "ruleset": raw.get("ruleset"),
        })

    return profiles


def build() -> Path:
    if not TEMPLATE.exists():
        sys.exit(f"Template introuvable : {TEMPLATE}")
    if not DATA_DIR.exists():
        sys.exit(f"Datapack introuvable : {DATA_DIR} — lancer le script depuis le dépôt du mod.")

    fr, en = lang_maps()
    objectives = collect_objectives(fr, en)
    profiles = collect_profiles(fr, en)

    tags = sorted({tag for objective in objectives for tag in objective["tags"]})
    types = sorted({objective["type"] for objective in objectives})
    # Les 5 types existent dans le code même si le datapack livré n'en utilise pas un :
    # l'union évite qu'un type non utilisé disparaisse du formulaire de création.
    types = sorted(set(types) | {
        "bingo:craft", "bingo:find", "bingo:kill_mob", "bingo:death", "bingo:action"})

    payload = {
        "generated_at": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "levels": {
            "min": scan_java_int(OBJECTIVE_JAVA, "MIN_LEVEL", 1),
            "max": scan_java_int(OBJECTIVE_JAVA, "MAX_LEVEL", 5),
        },
        # TILE_COUNT vaut SIZE² côté Java : on lit le côté, pas le produit.
        "tile_count": scan_java_int(BOARD_JAVA, "SIZE", 5) ** 2,
        "points_base": POINTS_BASE,
        "types": types,
        "tags": tags,
        "triggers": scan_triggers(),
        "objectives": objectives,
        "profiles": profiles,
    }

    template = TEMPLATE.read_text(encoding="utf-8")
    if PLACEHOLDER not in template:
        sys.exit(f"Placeholder '{PLACEHOLDER}' absent du template — template modifié ?")

    encoded = json.dumps(payload, ensure_ascii=False, indent=2)
    # `</script>` dans une donnée fermerait la balise du template avant l'heure.
    encoded = encoded.replace("</", "<\\/")

    OUTPUT.write_text(template.replace(PLACEHOLDER, encoded), encoding="utf-8", newline="\n")

    print(f"{OUTPUT.relative_to(ROOT)} généré")
    print(f"  {len(objectives)} objectifs · {len(profiles)} profils · "
          f"niveaux {payload['levels']['min']}..{payload['levels']['max']}")

    by_level = {}
    for objective in objectives:
        by_level[objective["level"]] = by_level.get(objective["level"], 0) + 1
    spread = " / ".join(
        f"{by_level.get(level, 0)} N{level}"
        for level in range(payload["levels"]["min"], payload["levels"]["max"] + 1))
    print(f"  répartition : {spread}")

    for profile in profiles:
        total = sum(profile["distribution"].values())
        if total != payload["tile_count"]:
            print(f"  ⚠ profil '{profile['name']}' somme à {total} au lieu de {payload['tile_count']}")

    return OUTPUT


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--open", action="store_true", help="ouvrir la page dans le navigateur")
    args = parser.parse_args()

    output = build()
    if args.open:
        webbrowser.open(output.as_uri())


if __name__ == "__main__":
    main()
