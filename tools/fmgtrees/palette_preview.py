"""
Export the ground palettes for review: textures on disk plus one HTML page.

    python -m fmgtrees.palette_preview [--out <dir>]

Writes, under --out (`tool-output/conquest_palettes` next to the repo by
default, outside git since everything in it is Conquest / ACRP artwork):

    textures/<biome>/<material>.png   top face, 4x4 blocks, tinted with that
                                      biome's colours and with Continuity
                                      repeat/random variants applied
    textures/_catalog/<material>.png  the same with neutral tints
    palette_data.json                 atlases + resolved palettes for the page
    index.html                        the review page, self-contained; open it
                                      in a browser
    palette_review.html               the same page without a document wrapper,
                                      for publishing

Textures resolve through the same stack the ArdaCraft profile uses: ACRP, then
the Conquest jar, then vanilla. A second, Conquest-only stack is exported too so
the page can show what the palettes look like without ACRP.
"""

import argparse
import base64
import hashlib
import io
import json
import os
import sys

from PIL import Image

from .crtextures import ACRP, CR_JAR, VANILLA, Ctm, PackStack, TopRenderer, hexrgb
from .make_biomes import BIOMES
from .palettes import MATERIALS, PALETTES, materials_used

RES = 32
UP_PROPS = {"layers": 8, "type": "bottom", "snowy": "false"}
NEUTRAL = {"grass": "#7fa34a", "foliage": "#6a9a3a", "sand": "#f4f2ef"}
HERE = os.path.dirname(os.path.abspath(__file__))


def _webp(img):
    # lossy colour, lossless alpha: a quarter of the PNG size, and at the
    # page's 16 px per block the difference is not visible
    buf = io.BytesIO()
    img.save(buf, "WEBP", quality=88, method=6)
    return buf.getvalue()


class Atlas:
    """Deduplicated image strips, addressed by content hash."""

    def __init__(self):
        self.data = {}

    def add(self, images):
        strip = Image.new("RGBA", (RES * len(images), RES), (0, 0, 0, 0))
        for i, im in enumerate(images):
            strip.paste(im, (i * RES, 0))
        raw = _webp(strip)
        key = hashlib.sha1(raw).hexdigest()[:12]
        self.data.setdefault(key, "data:image/webp;base64," + base64.b64encode(raw).decode())
        return key


def export_block(r, ctm, atlas, block_id, props, sides=True):
    """Face stack of one block: top with CTM variants, north side per layer count."""
    def pack(layers):
        out = []
        for l in layers:
            rule = l["rule"]
            entry = {"kind": l["kind"], "atlas": atlas.add(l["images"]),
                     "n": len(l["images"])}
            if rule and len(l["images"]) > 1:
                entry["method"] = rule["method"]
                entry["w"], entry["h"] = rule["w"], rule["h"]
                if rule["weights"]:
                    entry["weights"] = rule["weights"]
                if l["groups"]:
                    entry["groups"] = [[g["start"], g["count"], g["weights"]]
                                       for g in l["groups"]]
            out.append(entry)
        return out

    res = {"up": pack(r.layers(block_id, props, "up", ctm))}
    if sides:
        layered = r.blockstate(block_id) and "layers" in json.dumps(r.blockstate(block_id))
        heights = range(1, 9) if layered else [8]
        res["side"] = {}
        for k in heights:
            sp = dict(props, layers=k)
            res["side"][str(k)] = pack(r.layers(block_id, sp, "north", None))
    return res


def tinted_tile(r, ctm, block_id, tints, n=4):
    """n x n blocks of the top face, variants picked the way Continuity would."""
    t = {k: hexrgb(v) for k, v in tints.items()}
    layers = r.layers(block_id, UP_PROPS, "up", ctm)
    out = Image.new("RGBA", (RES * n, RES * n), (0, 0, 0, 0))
    for z in range(n):
        for x in range(n):
            cell = Image.new("RGBA", (RES, RES), (0, 0, 0, 0))
            for l in layers:
                idx = _variant(l["rule"], l["groups"], len(l["images"]), x, z)
                im = l["images"][idx]
                if l["kind"] and l["kind"] in t:
                    im = _multiply(im, t[l["kind"]])
                cell = Image.alpha_composite(cell, im)
            out.paste(cell, (x * RES, z * RES))
    return out


def _variant(rule, groups, count, x, z):
    """Image index for block (x, z): repeat by position, random by hash."""
    if not rule or count <= 1:
        return 0
    h = (x * 73856093 ^ z * 19349663) & 0xFFFFFFFF
    if rule["method"] == "repeat":
        tile = (x % rule["w"]) + (z % rule["h"]) * rule["w"]
        if not groups:
            return tile
        g = groups[tile % len(groups)]
        return g["start"] + _weighted(h, g["count"], g["weights"])
    return _weighted(h, count, rule["weights"])


def _weighted(h, count, weights):
    weights = weights or [1] * count
    pick = h % sum(weights)
    for i, w in enumerate(weights):
        if pick < w:
            return i
        pick -= w
    return 0


def _multiply(img, rgb):
    r, g, b, a = img.split()
    r = r.point(lambda v: v * rgb[0] // 255)
    g = g.point(lambda v: v * rgb[1] // 255)
    b = b.point(lambda v: v * rgb[2] // 255)
    return Image.merge("RGBA", (r, g, b, a))


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.palette_preview")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "..", "tool-output",
                                                  "conquest_palettes"))
    ap.add_argument("--jar", default=CR_JAR)
    ap.add_argument("--pack", action="append", default=None,
                    help="resource pack zip, highest priority first (default: ACRP)")
    ap.add_argument("--vanilla", default=VANILLA)
    ap.add_argument("--no-html", action="store_true")
    args = ap.parse_args(argv)

    packs = args.pack if args.pack is not None else [ACRP]
    full = PackStack.discover(args.jar, packs, args.vanilla)
    if not any(l == "conquest" for l in full.labels):
        print("Conquest jar not found: %s" % args.jar)
        return 1
    stacks = {"acrp": full, "cr": full.without("pack:")}
    names = {b["id"]: b for b in BIOMES}

    used = []
    for bid, p in PALETTES.items():
        for m in materials_used(p):
            if m not in used:
                used.append(m)
    fulls = set()
    for p in PALETTES.values():
        fulls.add(p["soil"])
    for m in used:
        fulls.add(MATERIALS[m]["full"])

    atlas = Atlas()
    data = {"packs": {}, "materials": {}, "biomes": [], "problems": []}
    for label, stack in stacks.items():
        r = TopRenderer(stack, res=RES)
        ctm = Ctm(stack)
        blocks = {}
        for m in used:
            mat = MATERIALS[m]
            if not r.exists(mat["layer"]):
                data["problems"].append("%s: %s missing in %s" % (m, mat["layer"], label))
                continue
            bs = json.dumps(r.blockstate(mat["layer"]))
            if "layers" not in bs:
                data["problems"].append("%s: %s has no layers property" % (m, mat["layer"]))
            blocks[mat["layer"]] = export_block(r, ctm, atlas, mat["layer"], UP_PROPS)
        for f in sorted(fulls):
            if r.exists(f):
                blocks[f] = export_block(r, ctm, atlas, f, {"snowy": "false"})
            else:
                data["problems"].append("%s missing in %s" % (f, label))
        data["packs"][label] = blocks
        print("%-5s %d blocks" % (label, len(blocks)))

    for m in used:
        data["materials"][m] = dict(MATERIALS[m])
        data["materials"][m]["usedBy"] = [b for b, p in PALETTES.items()
                                          if m in materials_used(p)]
    for bid, p in PALETTES.items():
        b = names.get(bid, {})
        data["biomes"].append(dict(p, id=bid, name=b.get("name", bid),
                                   azgaar=b.get("azgaar", []),
                                   status=b.get("status", "ready"),
                                   biomeNotes=b.get("notes", "")))
    data["atlas"] = atlas.data

    os.makedirs(args.out, exist_ok=True)

    # textures on disk: per biome with its own tints, plus a neutral catalog
    r = TopRenderer(stacks["acrp"], res=RES)
    ctm = Ctm(stacks["acrp"])
    cat = os.path.join(args.out, "textures", "_catalog")
    os.makedirs(cat, exist_ok=True)
    for m in used:
        tinted_tile(r, ctm, MATERIALS[m]["layer"], NEUTRAL).save(os.path.join(cat, m + ".png"))
    for bid, p in PALETTES.items():
        d = os.path.join(args.out, "textures", bid)
        os.makedirs(d, exist_ok=True)
        tints = dict(NEUTRAL, grass=p["tints"]["grass"], foliage=p["tints"]["foliage"])
        for m in materials_used(p):
            tinted_tile(r, ctm, MATERIALS[m]["layer"], tints).save(os.path.join(d, m + ".png"))

    raw = json.dumps(data, separators=(",", ":"))
    with open(os.path.join(args.out, "palette_data.json"), "w", encoding="utf-8") as fh:
        fh.write(raw)
    print("atlas images %d, data %.1f MB" % (len(atlas.data), len(raw) / 1e6))
    for p in data["problems"]:
        print("  problem:", p)

    if not args.no_html:
        tpl = os.path.join(HERE, "palette_preview.html")
        with open(tpl, encoding="utf-8") as fh:
            page = fh.read()
        page = page.replace("/*__PALETTE_DATA__*/null", raw)
        # palette_review.html is the bare page body (what gets published as an
        # artifact); index.html wraps it in a document for opening locally
        with open(os.path.join(args.out, "palette_review.html"), "w", encoding="utf-8") as fh:
            fh.write(page)
        with open(os.path.join(args.out, "index.html"), "w", encoding="utf-8") as fh:
            fh.write('<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
                     '<meta name="viewport" content="width=device-width, initial-scale=1">'
                     '</head><body>\n' + page + '\n</body></html>\n')
        print("wrote %s (%.1f MB)" % (os.path.join(args.out, "index.html"), len(page) / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
