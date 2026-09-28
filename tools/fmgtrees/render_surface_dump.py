"""
Draw a surface dump from `surface.cmd` (com.example.surface.SurfaceCli) with the
real Conquest / ACRP textures: a top-down map and a cross-section.

    python tools/fmgtrees/render_surface_dump.py run/surface/encia.json [--px 8] [--row N] [--cr]

Writes <dump>_top.png and <dump>_section.png next to the dump. The dump is the
game's own column decisions (same Java code), so this is what the ground would
look like in a Conquest world, minus plants, trees and props.
"""

import argparse
import json
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fmgtrees.crtextures import Ctm, PackStack, TopRenderer, hexrgb  # noqa: E402
from fmgtrees.palette_preview import _multiply, _variant  # noqa: E402

RES = 32
SAND = hexrgb("f4f2ef")


def rgb_of(n):
    return ((n >> 16) & 255, (n >> 8) & 255, n & 255)


class Tiles:
    """Tinted, variant-picked face images, cached."""

    def __init__(self, stack, px):
        self.r = TopRenderer(stack, res=RES)
        self.ctm = Ctm(stack)
        self.px = px
        self.layers = {}
        self.cache = {}

    def _layers(self, block, props, direction):
        key = (block, tuple(sorted(props.items())), direction)
        if key not in self.layers:
            self.layers[key] = self.r.layers(block, props, direction,
                                             self.ctm if direction == "up" else None)
        return self.layers[key]

    def face(self, block, props, direction, x, z, tints, size):
        ls = self._layers(block, props, direction)
        idx = tuple(_variant(l["rule"], l["groups"], len(l["images"]), x, z) for l in ls)
        key = (block, tuple(sorted(props.items())), direction, idx, tints, size)
        img = self.cache.get(key)
        if img is None:
            img = Image.new("RGBA", (RES, RES), (0, 0, 0, 0))
            for l, i in zip(ls, idx):
                im = l["images"][i]
                if l["kind"]:
                    col = {"sand": SAND, "foliage": tints[1]}.get(l["kind"], tints[0])
                    im = _multiply(im, col)
                img = Image.alpha_composite(img, im)
            img = img.resize((size, size), Image.BOX if size < RES else Image.NEAREST)
            self.cache[key] = img
        return img


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--px", type=int, default=8, help="pixels per block, top-down")
    ap.add_argument("--row", type=int, default=None, help="z row (0-based) for the section")
    ap.add_argument("--section-px", type=int, default=12)
    ap.add_argument("--cr", action="store_true", help="Conquest textures only, no ACRP")
    args = ap.parse_args(argv)

    d = json.load(open(args.dump, encoding="utf-8"))
    n = d["size"]
    x0, z0 = d["origin"]
    cols = d["data"]
    pals = d["palettes"]
    stack = PackStack.discover()
    if args.cr:
        stack = stack.without("pack:")
    tiles = Tiles(stack, args.px)

    def col(i, j):                      # i = x index, j = z index
        return cols[j * n + i]

    # ---- top-down ----------------------------------------------------------------
    P = args.px
    top = Image.new("RGBA", (n * P, n * P), (0, 0, 0, 255))
    shade = Image.new("RGBA", (n * P, n * P), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shade)
    for j in range(n):
        for i in range(n):
            s, h, ytop, k, m, p, water, fill, var, sub = col(i, j)
            pal = pals[p]
            tints = (rgb_of(pal["grass"]), rgb_of(pal["foliage"]))
            block = d["layer"][m] if water == -1 else d["full"][m]
            props = {"layers": k, "type": "bottom", "snowy": "false"}
            top.alpha_composite(tiles.face(block, props, "up", x0 + i, z0 + j, tints, P), (i * P, j * P))
            if water != -1:
                depth = water - (ytop + 1)
                a = int(255 * min(0.8, 0.45 + depth * 0.06))
                sd.rectangle([i * P, j * P, i * P + P - 1, j * P + P - 1], fill=rgb_of(pal["water"]) + (a,))
                continue
            e = col(min(n - 1, i + 1), j)[0] - col(max(0, i - 1), j)[0]
            so = col(i, min(n - 1, j + 1))[0] - col(i, max(0, j - 1))[0]
            sh = max(-0.28, min(0.2, -(e + so) * 0.08))
            if sh < 0:
                sd.rectangle([i * P, j * P, i * P + P - 1, j * P + P - 1], fill=(0, 0, 0, int(-sh * 255)))
            elif sh > 0:
                sd.rectangle([i * P, j * P, i * P + P - 1, j * P + P - 1], fill=(255, 255, 240, int(sh * 110)))
    top.alpha_composite(shade)
    row = args.row if args.row is not None else n // 2
    ImageDraw.Draw(top).line([(0, row * P + P // 2), (n * P, row * P + P // 2)], fill=(255, 255, 255, 160), width=1)
    base = os.path.splitext(args.dump)[0]
    top.convert("RGB").save(base + "_top.png")

    # ---- cross-section ------------------------------------------------------------
    S = args.section_px
    tops = [col(i, row)[2] for i in range(n)]
    lo = min(tops) - 6
    hi = max(max(tops), max((int(col(i, row)[6]) for i in range(n) if col(i, row)[6] != -1), default=0)) + 3
    sec = Image.new("RGBA", (n * S, (hi - lo + 1) * S), (24, 26, 23, 255))
    draw = ImageDraw.Draw(sec)
    for i in range(n):
        s, h, ytop, k, m, p, water, fill, var, sub = col(i, row)
        pal = pals[p]
        tints = (rgb_of(pal["grass"]), rgb_of(pal["foliage"]))
        stone = d["full"][pal["stone"]]
        if water != -1:
            under, depth_under = d["full"][m], fill
            ytop_draw, top_block, top_k = ytop, d["full"][m], 8
        else:
            under = pal["soil"] if d["skin"][m] else d["full"][m]
            depth_under = fill if d["skin"][m] else max(1, fill)
            ytop_draw, top_block, top_k = ytop, d["layer"][m], k
        py = lambda y: (hi - y) * S                                   # noqa: E731
        for y in range(lo, ytop_draw):
            b = under if ytop_draw - y <= depth_under else stone
            sec.alpha_composite(tiles.face(b, {"snowy": "false"}, "north", 0, 0, tints, S), (i * S, py(y)))
        sec.alpha_composite(tiles.face(top_block, {"layers": top_k, "type": "bottom", "snowy": "false"},
                                       "north", 0, 0, tints, S), (i * S, py(ytop_draw)))
        if water != -1:
            wtop = (hi + 1 - water) * S
            bot = (hi + 1 - (ytop + 1)) * S
            draw.rectangle([i * S, int(wtop), i * S + S - 1, int(bot)], fill=rgb_of(pal["water"]) + (140,))
    sec.convert("RGB").save(base + "_section.png")
    print("wrote %s_top.png (%dx%d) and %s_section.png" % (base, n * P, n * P, base))
    used = sorted({pals[c[5]]["id"] for c in cols})
    print("palettes:", ", ".join(used))


if __name__ == "__main__":
    sys.exit(main())
