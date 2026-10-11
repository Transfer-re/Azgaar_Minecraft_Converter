"""
uNmINeD colours for FMG worlds built from Conquest Reforged blocks.

uNmINeD colours blocks by name/tag; every `conquest:` block is unknown to it and
renders magenta. This writes the four `custom.*.txt` files uNmINeD reads from
its `config/` folder (GUI and CLI alike) so the map shows what the game shows:

    custom.blockstyles.txt   one colour per Conquest block, the average of its
                             top face as the game draws it (same resource stack
                             as the palette review: ACRP > CR jar > vanilla, with
                             Continuity repeat/random variants averaged in).
                             Biome-tinted blocks (grass, moss, leaves) get one
                             line per FMG biome with that biome's tint applied.
    custom.blocktags.txt     cross-model plants (no top face) as #grass so they
                             do not count as ground for relief shading; FMG's
                             invisible road-marker light block as #air
    custom.biometints.txt    FMG biome grass/foliage/water tints, for the vanilla
                             blocks in the world (water above all)
    custom.biomecolors.txt   FMG biomes for uNmINeD's biome view

    python -m fmgtrees.unmined_config [--out <dir>] [--cr]

The default --out is `tool-output/unmined` next to the repo.

Copy the files into uNmINeD's `config/` folder, replacing the example files
of the same name.

Two uNmINeD rules the output depends on (checked against 0.20.11-dev):
- in `biome/block = colour` the line is split at the FIRST '/', and FMG biome
  ids contain one (`fantasymapgenerator:conquest/meadow`), so the biome is
  written `fantasymapgenerator:conquest?meadow` ('?' = any one character);
- when several lines match a block, the LAST one wins, so the biome-free
  default comes first and the per-biome lines after it.
"""

import argparse
import glob
import json
import os

import numpy as np

from .crtextures import ACRP, CR_JAR, VANILLA, Ctm, PackStack, TopRenderer, hexrgb

RES = 16
# state used to pick the variant: full-height layer, bottom slab, no snow
UP_PROPS = {"layers": "8", "type": "bottom", "snowy": "false"}
SAND = hexrgb("f4f2ef")                  # ACRP sand colormap, near-neutral
# tints for biomes FMG does not define (vanilla beach/river/ocean): plains
DEFAULT_TINT = (hexrgb("91bd59"), hexrgb("77ab2f"))
# Polytone colormap families that follow the grass tint; the rest (oak, beech,
# larch, spruce_dark, ...) are per-tree foliage maps
GRASS_KINDS = {"grass", "grasses", "mosses"}

HERE = os.path.dirname(os.path.abspath(__file__))
BIOME_DIR = os.path.join(HERE, "..", "..", "src", "main", "resources", "data",
                         "fantasymapgenerator", "worldgen", "biome")


def fmg_biomes(root=BIOME_DIR):
    """[(biome id, grass, foliage, water)] from the mod's biome JSONs."""
    out = []
    for path in sorted(glob.glob(os.path.join(root, "**", "*.json"), recursive=True)):
        rel = os.path.relpath(path, root).replace(os.sep, "/")[:-5]
        eff = json.load(open(path, encoding="utf-8")).get("effects", {})
        if "grass_color" not in eff:
            continue
        rgb = lambda n: ((n >> 16) & 255, (n >> 8) & 255, n & 255)
        out.append(("fantasymapgenerator:" + rel, rgb(eff["grass_color"]),
                    rgb(eff.get("foliage_color", eff["grass_color"])),
                    rgb(eff.get("water_color", 0x3F76E4))))
    return out


def _arr(img):
    return np.asarray(img, dtype=np.float32) / 255.0


class BlockColours:

    def __init__(self, stack):
        self.r = TopRenderer(stack, res=RES)
        self.ctm = Ctm(stack)

    def layers(self, block_id):
        """([(tint kind or None, mean RGBA array)], is_plant) back-most first.

        The top face when it shows anything; else the side (cross-model plants
        have no top, and some props' tops sample a transparent patch of their
        texture); else the particle texture.
        """
        up = self._mean(self.r.layers(block_id, UP_PROPS, "up", self.ctm))
        if self.composite(up, *DEFAULT_TINT) is not None:
            return up, False
        side = self._mean(self.r.layers(block_id, UP_PROPS, "north", None))
        if self.composite(side, *DEFAULT_TINT) is not None:
            return side, not up          # no top face at all: a plant
        tex = self._particle(block_id)
        if tex is not None:
            return [(None, _arr(tex.resize((RES, RES))))], False
        return [], False

    @staticmethod
    def _mean(ls):
        return [(l["kind"], np.mean([_arr(i) for i in l["images"]], axis=0)) for l in ls]

    def _particle(self, block_id):
        """Particle (else any) texture of any model the block can use.

        Multipart pieces (beams, posts, walls) show nothing for the default
        state, so every model in the blockstate is a candidate here.
        """
        mids = [m for m, _ in self.r.models_for(block_id, UP_PROPS)]
        bs = self.r.blockstate(block_id) or {}
        applies = list(bs.get("variants", {}).values())
        applies += [p.get("apply") for p in bs.get("multipart", [])]
        for a in applies:
            for choice in (a if isinstance(a, list) else [a]):
                if isinstance(choice, dict) and "model" in choice:
                    mids.append(choice["model"])
        for mid in mids:
            textures = self.r.model(mid)["textures"]
            for key in ["particle"] + sorted(textures):
                ref = self.r._resolve_ref(textures, textures.get(key, ""))
                tex = self.r.texture(ref) if ref else None
                if tex is not None:
                    return tex
        return None

    @staticmethod
    def tint_for(kind, grass, foliage):
        if kind == "sand":
            return SAND
        return grass if kind in GRASS_KINDS else foliage

    @staticmethod
    def composite(layers, grass, foliage):
        """Average colour (r, g, b) of the composited face, or None if empty."""
        canvas = np.zeros((RES, RES, 4), dtype=np.float32)
        for kind, img in layers:
            img = img.copy()
            if kind:
                img[..., :3] *= np.array(BlockColours.tint_for(kind, grass, foliage)) / 255.0
            a = img[..., 3:4]
            canvas[..., :3] = img[..., :3] * a + canvas[..., :3] * (1 - a)
            canvas[..., 3:4] = a + canvas[..., 3:4] * (1 - a)
        w = canvas[..., 3].sum()
        if w < 1e-3:
            return None
        rgb = (canvas[..., :3] * canvas[..., 3:4]).sum(axis=(0, 1)) / w
        return tuple(int(round(c * 255)) for c in rgb)


def _hex(rgb):
    return "#%02x%02x%02x" % rgb


def _pattern(biome_id):
    # uNmINeD splits "biome/block" at the first '/', so stand in '?' for it
    return biome_id.replace("/", "?")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.unmined_config")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "..", "tool-output", "unmined"))
    ap.add_argument("--cr", action="store_true", help="Conquest textures only, no ACRP")
    args = ap.parse_args(argv)

    packs = () if args.cr else (ACRP,)
    stack = PackStack.discover(jar=CR_JAR, packs=packs, vanilla=VANILLA)
    if "conquest" not in stack.labels:
        raise SystemExit("Conquest Reforged jar not found: " + CR_JAR)
    bc = BlockColours(stack)
    biomes = fmg_biomes()
    names = sorted(n[len("assets/conquest/blockstates/"):-5]
                   for n in stack.list("assets/conquest/blockstates/") if n.endswith(".json"))

    plain, tinted, plants, skipped = [], [], [], []
    for name in names:
        bid = "conquest:" + name
        layers, plant = bc.layers(bid)
        base = bc.composite(layers, *DEFAULT_TINT)
        if base is None:
            skipped.append(bid)
            continue
        if plant:
            plants.append(bid)
        per_biome = any(k and k != "sand" for k, _ in layers)
        if per_biome:
            tinted.append((bid, base, [(b, bc.composite(layers, g, f)) for b, g, f, _ in biomes]))
        else:
            plain.append((bid, base))

    os.makedirs(args.out, exist_ok=True)
    head = ["// Generated by tools/fmgtrees/unmined_config.py for FMG + Conquest Reforged",
            "// (textures: %s). Do not edit; re-run the script instead." % " > ".join(stack.labels),
            ""]

    with open(os.path.join(args.out, "custom.blockstyles.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head) + "\n")
        f.write("// anything not listed below (no usable texture, newer CR version): stone grey\n")
        f.write("conquest:* = #7d7d7d\n\n")
        f.write("// Conquest blocks: top-face colour\n")
        for bid, rgb in plain:
            f.write("%s = %s\n" % (bid, _hex(rgb)))
        f.write("\n// Biome-tinted Conquest blocks, outside FMG biomes (plains tint)\n")
        for bid, rgb, _ in tinted:
            f.write("%s = %s\n" % (bid, _hex(rgb)))
        f.write("\n// ...and per FMG biome (must come after the lines above: last match wins)\n")
        for bid, _, per in tinted:
            for biome, rgb in per:
                if rgb is not None:
                    f.write("%s/%s = %s\n" % (_pattern(biome), bid, _hex(rgb)))

    with open(os.path.join(args.out, "custom.blocktags.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head) + "\n")
        f.write("// FMG's road marker (an invisible level-0 light block above every road\n"
                "// block, see RoadMarker.java); uNmINeD has no style for it -> magenta\n")
        f.write("minecraft:light = #air\n\n")
        f.write("// Conquest plants without a top face: not ground\n")
        for bid in plants:
            f.write("%s = #grass\n" % bid)

    with open(os.path.join(args.out, "custom.biometints.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head) + "\n")
        f.write("// biome = grass, foliage, water\n")
        for b, g, fo, w in biomes:
            f.write("%s = %s, %s, %s\n" % (b, _hex(g), _hex(fo), _hex(w)))

    with open(os.path.join(args.out, "custom.biomecolors.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head) + "\n")
        for b, g, _, _ in biomes:
            f.write("%s = %s\n" % (b, _hex(g)))

    print("%d plain + %d biome-tinted Conquest blocks (%d plants), %d FMG biomes, %d skipped -> %s"
          % (len(plain), len(tinted), len(plants), len(biomes), len(skipped), os.path.abspath(args.out)))
    if skipped:
        print("no texture:", ", ".join(skipped[:20]) + (" ..." if len(skipped) > 20 else ""))


if __name__ == "__main__":
    main()
