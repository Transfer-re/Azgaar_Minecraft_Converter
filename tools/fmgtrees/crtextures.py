"""
Render what the top of a block looks like, the way the game would draw it.

Resolves a block id through the same resource stack the client uses -- resource
packs first, then the Conquest jar, then vanilla -- following blockstate ->
model -> parent chain -> textures, and composites every element face that forms
the visible top (base texture plus any tinted overlay) into one square image.

This exists for palette review: the terrain surface is made of layer blocks, and
what a player sees of a biome is mostly these top faces, so picking palettes by
name alone is guessing. Tints matter as much as textures -- most Conquest grass,
moss and overgrown blocks are greyscale with a tintindex -- so the renderer
takes the grass/foliage/sand colours to apply.

Only the up face is drawn. Side fringes (`grass_block_ext*`, anything with a
`yoffset`) are skipped, and element rotations are ignored; for flat terrain
blocks neither changes the top view.

    from fmgtrees.crtextures import PackStack, TopRenderer
    stack = PackStack.discover()
    img = TopRenderer(stack).top("conquest:taiga_grass_layer",
                                 tints={"grass": (0x86, 0xa0, 0x60)})
"""

import io
import json
import os
import zipfile

try:
    from PIL import Image
except ImportError:                      # pragma: no cover - optional dependency
    Image = None

PROFILE = os.path.join(os.environ.get("APPDATA", ""), "ModrinthApp", "profiles",
                       "ArdaCraft")
CR_JAR = os.path.join(PROFILE, "mods", "ConquestReforged-fabric-1.20.1-1.7.0.jar")
ACRP = os.path.join(PROFILE, "resourcepacks", "ACRP 1.2.8-Fabric.zip")
VANILLA = os.path.join(os.environ.get("APPDATA", ""), "ModrinthApp", "meta",
                       "versions", "1.20.1-0.18.4", "1.20.1-0.18.4.jar")

# Side-only fringe models that stick out of grass-like blocks. They carry no
# part of the top face, and some are shifted down with a `yoffset`.
SKIP_MODELS = ("block/grass_block_ext", "block/grass_block_sides",
               "minecraft:block/grass_block_ext", "minecraft:block/grass_block_sides")


class PackStack:
    """Ordered list of zip-backed resource sources, highest priority first."""

    def __init__(self, sources):
        self.sources = []                # (label, ZipFile)
        for label, path in sources:
            if path and os.path.exists(path):
                self.sources.append((label, zipfile.ZipFile(path)))
        self._names = [set(z.namelist()) for _, z in self.sources]

    @classmethod
    def discover(cls, jar=CR_JAR, packs=(ACRP,), vanilla=VANILLA):
        src = [("pack:" + os.path.basename(p), p) for p in packs]
        src += [("conquest", jar), ("vanilla", vanilla)]
        return cls(src)

    @property
    def labels(self):
        return [label for label, _ in self.sources]

    def without(self, label_prefix):
        """A view of this stack with the matching sources dropped."""
        clone = PackStack([])
        keep = [i for i, (l, _) in enumerate(self.sources)
                if not l.startswith(label_prefix)]
        clone.sources = [self.sources[i] for i in keep]
        clone._names = [self._names[i] for i in keep]
        return clone

    def read(self, path):
        for (label, z), names in zip(self.sources, self._names):
            if path in names:
                return z.read(path)
        return None

    def origin(self, path):
        for (label, _), names in zip(self.sources, self._names):
            if path in names:
                return label
        return None

    def json(self, path):
        raw = self.read(path)
        return None if raw is None else json.loads(raw.decode("utf-8"))

    def list(self, prefix):
        out = set()
        for names in self._names:
            out.update(n for n in names if n.startswith(prefix))
        return out


def _split(rid, default_ns="minecraft"):
    if ":" in rid:
        ns, path = rid.split(":", 1)
    else:
        ns, path = default_ns, rid
    return ns, path


def _parse_variant_key(key):
    if not key:
        return {}
    # a few CR blockstates have stray separators ("facing=north,"), skip those parts
    return dict(kv.split("=", 1) for kv in key.split(",") if "=" in kv)


def _when_matches(when, props):
    if when is None:
        return True
    if "OR" in when:
        return any(_when_matches(w, props) for w in when["OR"])
    if "AND" in when:
        return all(_when_matches(w, props) for w in when["AND"])
    for k, v in when.items():
        want = str(v).lower().split("|")
        have = props.get(k)
        if have is None:
            # unknown property: assume its default, which for the boolean
            # fringe/snow toggles on terrain blocks is false
            if "false" not in want:
                return False
        elif str(have).lower() not in want:
            return False
    return True


class Polytone:
    """Which blocks a resource pack re-tints through Polytone, and with what.

    ACRP drives grass, sand and many plants from colormaps keyed by vanilla
    biome id, and a colormap can differ per tintindex (mossy sand: sand base on
    index 2, grass overlay on index 0). For review we only need the colour
    *family*, so this maps (block id, tintindex) -> 'grass' | 'sand' | '<name>'.

    Note Polytone's id mapper does `getOrDefault(biome, 0)`: a biome id the
    pack's mapper does not list samples column 0, whatever its own biome JSON
    says. See docs/conquest/palettes.md.
    """

    def __init__(self, stack):
        self.kind = {}                   # block id -> {tintindex: kind}
        prefix = "assets/polytone/polytone/block_modifiers/"
        for path in sorted(stack.list(prefix)):
            if not path.endswith(".json"):
                continue
            data = stack.json(path) or {}
            cmap = data.get("colormap")
            if cmap is None:
                continue
            name = os.path.basename(path)[:-5]
            if isinstance(cmap, str):
                per = {None: cmap.split(":")[-1]}
            elif isinstance(cmap, dict) and all(k.isdigit() for k in cmap):
                per = {int(k): v.split(":")[-1] for k, v in cmap.items()}
            else:                        # inline colormap: the modifier's own png
                per = {None: name}
            per = {k: {"grasses": "grass"}.get(v, v) for k, v in per.items()}
            for t in data.get("targets", []):
                self.kind[t] = per

    def get(self, block_id, tintindex=0):
        per = self.kind.get(block_id)
        if not per:
            return None
        return per.get(tintindex, per.get(None))


class TopRenderer:

    def __init__(self, stack, res=32):
        if Image is None:
            raise RuntimeError("Pillow is required for texture rendering")
        self.stack = stack
        self.res = res
        self.polytone = Polytone(stack)
        self._models = {}
        self._tex = {}

    # ---- resolution --------------------------------------------------------

    def blockstate(self, block_id):
        ns, path = _split(block_id)
        return self.stack.json("assets/%s/blockstates/%s.json" % (ns, path))

    def exists(self, block_id):
        ns, path = _split(block_id)
        return self.stack.read("assets/%s/blockstates/%s.json" % (ns, path)) is not None

    def models_for(self, block_id, props=None):
        """[(model_id, y_rotation)] for the given state, first random variant."""
        props = {k: str(v) for k, v in (props or {}).items()}
        bs = self.blockstate(block_id)
        if bs is None:
            return []
        picked = []
        if "variants" in bs:
            best, best_score = None, -1
            for key, val in bs["variants"].items():
                cond = _parse_variant_key(key)
                if any(k in props and props[k] != v for k, v in cond.items()):
                    continue
                score = sum(1 for k in cond if k in props)
                if score > best_score:
                    best, best_score = val, score
            if best is None:
                best = next(iter(bs["variants"].values()))
            picked.append(best)
        else:
            for part in bs.get("multipart", []):
                if _when_matches(part.get("when"), props):
                    picked.append(part["apply"])
        out = []
        for apply in picked:
            choice = apply[0] if isinstance(apply, list) else apply
            if "yoffset" in choice or choice["model"] in SKIP_MODELS:
                continue
            out.append((choice["model"], int(choice.get("y", 0))))
        return out

    def model(self, model_id):
        if model_id in self._models:
            return self._models[model_id]
        textures, elements, cur, seen = {}, None, model_id, set()
        while cur and cur not in seen:
            seen.add(cur)
            ns, path = _split(cur)
            m = self.stack.json("assets/%s/models/%s.json" % (ns, path))
            if m is None:
                break
            for k, v in m.get("textures", {}).items():
                textures.setdefault(k, v)
            if elements is None and "elements" in m:
                elements = m["elements"]
            cur = m.get("parent")
        res = {"textures": textures, "elements": elements or []}
        self._models[model_id] = res
        return res

    def _resolve_ref(self, textures, ref):
        for _ in range(16):
            if not ref.startswith("#"):
                return ref
            ref = textures.get(ref[1:], "")
        return None

    def texture(self, tex_id):
        if tex_id in self._tex:
            return self._tex[tex_id]
        if tex_id.startswith("path:"):
            raw = self.stack.read(tex_id[5:])
        else:
            ns, path = _split(tex_id)
            raw = self.stack.read("assets/%s/textures/%s.png" % (ns, path))
        img = None
        if raw is not None:
            img = Image.open(io.BytesIO(raw)).convert("RGBA")
            w, h = img.size
            if h > w:                    # animated strip: first frame
                img = img.crop((0, 0, w, w))
        self._tex[tex_id] = img
        return img

    def texture_origin(self, tex_id):
        ns, path = _split(tex_id)
        return self.stack.origin("assets/%s/textures/%s.png" % (ns, path))

    # ---- drawing -----------------------------------------------------------

    def faces(self, block_id, props=None, direction="up"):
        """Every face forming the visible `direction` side, back-most first.

        `up` is the top seen from above; `north` is the side seen from the
        north, which is what a terrain cross-section shows.
        """
        faces = []
        for order, (mid, yrot) in enumerate(self.models_for(block_id, props)):
            m = self.model(mid)
            for i, el in enumerate(m["elements"]):
                f = el.get("faces", {}).get(direction)
                if not f:
                    continue
                tex = self._resolve_ref(m["textures"], f.get("texture", ""))
                if not tex:
                    continue
                depth = el["to"][1] if direction == "up" else -el["from"][2]
                faces.append({"depth": depth, "from": el["from"], "to": el["to"],
                              "uv": f.get("uv"), "rot": int(f.get("rotation", 0)),
                              "tint": f.get("tintindex"), "tex": tex,
                              "yrot": yrot if direction == "up" else 0,
                              "order": (order, i)})
        if not faces:
            return []
        front = max(f["depth"] for f in faces)
        # overlays sit a hair in front of their base; anything a full unit
        # behind is a recessed step of the model (a lip, an under-slab)
        faces = [f for f in faces if f["depth"] >= front - 1.0]
        faces.sort(key=lambda f: (f["depth"], f["order"]))
        return faces

    def top_faces(self, block_id, props=None):
        return self.faces(block_id, props, "up")

    @staticmethod
    def _face_rect(f, direction):
        """(dest rect in 0..16 units, default uv) for one face."""
        fr, to = f["from"], f["to"]
        if direction == "up":
            rect = (fr[0], fr[2], to[0], to[2])
        else:                            # north: +x is on the viewer's left
            rect = (16 - to[0], 16 - to[1], 16 - fr[0], 16 - fr[1])
        return rect, list(rect)

    def face_layer(self, f, direction="up", tex_id=None):
        """One face drawn untinted onto a transparent R x R canvas."""
        R = self.res
        layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        tex = self.texture(tex_id or f["tex"])
        if tex is None:
            return layer
        tw = tex.size[0]
        rect, default_uv = self._face_rect(f, direction)
        uv = f["uv"] or default_uv
        u0, v0, u1, v1 = [c * tw / 16.0 for c in uv]
        box = (min(u0, u1), min(v0, v1), max(u0, u1), max(v0, v1))
        box = tuple(int(round(c)) for c in box)
        if box[2] - box[0] < 1 or box[3] - box[1] < 1:
            return layer
        region = tex.crop(box)
        if u1 < u0:
            region = region.transpose(Image.FLIP_LEFT_RIGHT)
        if v1 < v0:
            region = region.transpose(Image.FLIP_TOP_BOTTOM)
        if f["rot"]:
            region = region.rotate(-f["rot"], expand=True)
        dx0, dy0 = max(0, int(round(rect[0] * R / 16.0))), max(0, int(round(rect[1] * R / 16.0)))
        dx1, dy1 = min(R, int(round(rect[2] * R / 16.0))), min(R, int(round(rect[3] * R / 16.0)))
        if dx1 <= dx0 or dy1 <= dy0:
            return layer
        layer.paste(region.resize((dx1 - dx0, dy1 - dy0), Image.NEAREST), (dx0, dy0))
        if f["yrot"]:
            layer = layer.rotate(-f["yrot"])
        return layer

    def layers(self, block_id, props=None, direction="up", ctm=None):
        """The face stack as data: [{kind, rule, images, tex}], back-most first.

        `kind` is the tint family (None when untinted); `images` holds one
        untinted R x R image per texture variant -- a single entry unless a
        Continuity rule applies, in which case `rule` says how the variant is
        picked per block position (see Ctm).
        """
        out = []
        for f in self.faces(block_id, props, direction):
            rule = ctm.rule(f["tex"], direction) if ctm else None
            groups = None
            if rule:
                # a tile can itself be matched by a `random` rule (CR's grass
                # top is a 4x4 repeat of placeholders, each a random pick), so
                # each tile expands to a group of weighted variants
                imgs, groups = [], []
                for t in rule["tiles"]:
                    sub = ctm.rule(t, direction)
                    if sub and sub["method"] == "random":
                        tiles, weights = sub["tiles"], sub["weights"]
                    else:
                        tiles, weights = [t], None
                    groups.append({"start": len(imgs), "count": len(tiles),
                                   "weights": weights})
                    imgs += [self.face_layer(f, direction, v) for v in tiles]
                if all(g["count"] == 1 for g in groups):
                    groups = None
            else:
                imgs = [self.face_layer(f, direction)]
            kind = self.tint_kind(block_id, f["tint"]) if f["tint"] is not None else None
            out.append({"kind": kind, "rule": rule, "groups": groups,
                        "images": imgs, "tex": f["tex"]})
        return out

    def top(self, block_id, props=None, tints=None, tint_kind=None, direction="up"):
        """RGBA image of one face of the block at `self.res` pixels.

        `tints` maps colour family -> (r, g, b): 'grass', 'foliage', 'sand', or
        a Polytone modifier name. `tint_kind` forces a family for tintindex
        faces; otherwise Polytone decides, then the block name does.
        """
        tints = tints or {}
        R = self.res
        canvas = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        for f in self.faces(block_id, props, direction):
            layer = self.face_layer(f, direction)
            if f["tint"] is not None:
                col = tints.get(tint_kind or self.tint_kind(block_id, f["tint"]))
                if col is not None:
                    layer = _multiply(layer, col)
            canvas = Image.alpha_composite(canvas, layer)
        return canvas

    def tint_kind(self, block_id, tintindex=0):
        kind = self.polytone.get(block_id, tintindex)
        if kind:
            return kind
        short = block_id.split(":")[-1]
        if any(w in short for w in ("leaves", "needles", "leaf_", "_leaf")):
            return "foliage"
        return "grass"

    def is_tinted(self, block_id, props=None):
        return any(f["tint"] is not None for f in self.top_faces(block_id, props))

    def top_textures(self, block_id, props=None):
        """Texture ids used by the top face, for provenance in the review."""
        return [f["tex"] for f in self.top_faces(block_id, props)]


class Ctm:
    """Continuity/OptiFine `repeat` and `random` rules, indexed by texture.

    Conquest leans on these for nearly every terrain surface: the grass top is
    one 4x4-block image, tan sand an 8x8 one, gravel and most soils pick among
    weighted variants. Judging a palette from the single base texture
    overstates how tiled it looks, so the review applies them.

    Only rules with `matchTiles` are read, and only `repeat` and `random`,
    with one level of chaining (a repeat tile matched by its own random rule).
    `overlay` (the grass fringe CR draws onto neighbouring dirt, sand, gravel
    and small stones) and connected `ctm` are not reproduced.
    """

    def __init__(self, stack):
        self.rules = {}                  # (tex id, 'up'|'side') -> rule
        root = "assets/minecraft/optifine/ctm/"
        order = {label: i for i, label in enumerate(stack.labels)}
        found = []
        for path in stack.list(root):
            if not path.endswith(".properties"):
                continue
            raw = stack.read(path).decode("utf-8", "replace")
            d = {}
            for line in raw.splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    d[k.strip()] = v.strip()
            if d.get("method") not in ("repeat", "random") or "matchTiles" not in d:
                continue
            found.append((order.get(stack.origin(path), 99), path, d))
        found.sort()
        for _, path, d in found:
            folder = os.path.dirname(path)
            tiles = self._tiles(stack, folder, d.get("tiles", ""))
            if not tiles:
                continue
            faces = set(d.get("faces", "all").split())
            rule = {"method": d["method"], "tiles": tiles,
                    "w": int(d.get("width", 1)), "h": int(d.get("height", 1)),
                    "weights": [int(w) for w in d.get("weights", "").split()] or None}
            if rule["weights"] and len(rule["weights"]) != len(tiles):
                rule["weights"] = None
            for t in d["matchTiles"].split():
                tex = self._norm(t, folder)
                if tex is None:
                    continue
                if faces & {"all", "top"}:
                    self.rules.setdefault((tex, "up"), rule)
                if faces & {"all", "sides", "north", "south", "east", "west"}:
                    self.rules.setdefault((tex, "side"), rule)

    @staticmethod
    def _norm(t, folder):
        """matchTiles entry -> texture id, or 'path:' for a chained CTM tile."""
        if t.startswith("./"):
            return "path:%s/%s" % (folder, t[2:])
        if t.startswith("optifine/"):
            return "path:assets/minecraft/" + t
        if t.endswith(".png"):
            return None
        ns, p = t.split(":", 1) if ":" in t else ("minecraft", t)
        if p.startswith("textures/"):
            p = p[len("textures/"):]
        if not p.startswith("block/"):
            p = "block/" + p
        return "%s:%s" % (ns, p)

    @staticmethod
    def _tiles(stack, folder, spec):
        out = []
        for part in spec.split():
            if "-" in part and part.replace("-", "").isdigit():
                a, b = part.split("-")
                names = [str(n) for n in range(int(a), int(b) + 1)]
            else:
                names = [part]
            for n in names:
                if ":" in n or "/" in n:
                    ns, p = _split(n)
                    p = p if p.startswith("textures/") else "textures/" + p
                    path = "assets/%s/%s%s" % (ns, p, "" if p.endswith(".png") else ".png")
                else:
                    path = "%s/%s%s" % (folder, n, "" if n.endswith(".png") else ".png")
                if stack.read(path) is None:
                    return []
                out.append("path:" + path)
        return out

    def rule(self, tex_id, direction="up"):
        return self.rules.get((tex_id, "up" if direction == "up" else "side"))


def _multiply(img, rgb):
    r, g, b, a = img.split()
    r = r.point(lambda v: v * rgb[0] // 255)
    g = g.point(lambda v: v * rgb[1] // 255)
    b = b.point(lambda v: v * rgb[2] // 255)
    return Image.merge("RGBA", (r, g, b, a))


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
