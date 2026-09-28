"""
Render a voxel model as a small isometric PNG, for the Axiom blueprint picker.

Axiom renders its own thumbnail when it saves a blueprint, but a blueprint
written from outside the game has never been rendered, and `bpwrite` can only
put a blank placeholder there. Browsing 165 identically-blank African trees in
the picker is unpleasant, so this draws a rough silhouette instead: enough to
tell a baobab from a palm at a glance, not a substitute for opening it.

Colours come from the Conquest jar's own textures (mean RGB over the opaque
pixels), so a mapped tree thumbnails in roughly the colours it will have in
world. Blocks that are not in the jar fall back to a short vanilla table, then
to grey. Everything here is optional -- if Pillow or the jar is missing, the
caller gets None and keeps the blank placeholder.
"""

import io
import os
import struct
import zipfile

try:
    from PIL import Image, ImageDraw
except ImportError:                                    # pragma: no cover
    Image = None

# Rough mean colours for the vanilla blocks the packs use, for when a block is
# not a Conquest one. Only wood and leaves matter; anything else lands on grey.
VANILLA = {
    "oak_log": (150, 120, 71), "oak_wood": (109, 85, 50),
    "birch_log": (216, 212, 203), "birch_wood": (198, 190, 163),
    "spruce_log": (114, 85, 49), "spruce_wood": (58, 39, 20),
    "jungle_log": (149, 109, 76), "jungle_wood": (85, 66, 30),
    "acacia_log": (169, 90, 44), "acacia_wood": (103, 96, 86),
    "dark_oak_log": (60, 46, 26), "dark_oak_wood": (60, 46, 26),
    "mangrove_log": (121, 26, 31), "mangrove_wood": (98, 44, 39),
    "cherry_log": (219, 165, 165), "cherry_wood": (230, 180, 180),
    "oak_leaves": (72, 118, 44), "birch_leaves": (110, 140, 60),
    "spruce_leaves": (51, 84, 51), "jungle_leaves": (55, 120, 30),
    "acacia_leaves": (110, 140, 45), "dark_oak_leaves": (60, 105, 35),
    "mangrove_leaves": (60, 110, 40), "pale_oak_leaves": (160, 175, 150),
    "vine": (60, 110, 40),
}
GREY = (130, 130, 130)

# Most Conquest leaves carry a tintindex, so the texture is luminance and the
# biome supplies the hue -- `ash_tree_leaves` averages 166,158,158 and would
# thumbnail as a white blob. There is no biome here, so a nominal foliage
# colour stands in, applied the way Minecraft applies one: a multiply.
FOLIAGE_TINT = (96, 148, 62)
GREYSCALE_SPREAD = 14


def _tint(short, rgb):
    if "leaves" not in short and "needles" not in short and "lianas" not in short:
        return rgb
    if max(rgb) - min(rgb) > GREYSCALE_SPREAD:
        return rgb                      # already coloured; not a tinted texture
    return tuple(c * t // 255 for c, t in zip(rgb, FOLIAGE_TINT))


class Palette:
    """block id -> mean RGB, read out of the Conquest jar once and cached."""

    def __init__(self, jar_path=None):
        self.cache = {}
        self.textures = {}
        self.zip = None
        if jar_path and os.path.exists(jar_path) and Image is not None:
            try:
                self.zip = zipfile.ZipFile(jar_path)
                self.textures = {
                    n.rsplit("/", 1)[-1][:-4]: n for n in self.zip.namelist()
                    if n.startswith("assets/conquest/textures/block/")
                    and n.endswith(".png")
                }
            except Exception:
                self.zip = None

    def _from_jar(self, short):
        for key in (short, short + "_1", short + "_top", short + "_side"):
            path = self.textures.get(key)
            if not path:
                continue
            try:
                im = Image.open(io.BytesIO(self.zip.read(path))).convert("RGBA")
            except Exception:
                return None
            # Animated textures are a vertical strip; only the first frame.
            if im.height > im.width:
                im = im.crop((0, 0, im.width, im.width))
            px = [p for p in im.getdata() if p[3] > 128]
            if not px:
                return None
            n = len(px)
            return (sum(p[0] for p in px) // n,
                    sum(p[1] for p in px) // n,
                    sum(p[2] for p in px) // n)
        return None

    def get(self, block):
        if block in self.cache:
            return self.cache[block]
        short = block.split(":", 1)[-1]
        rgb = None
        if self.zip is not None and block.startswith("conquest:"):
            rgb = self._from_jar(short)
        if rgb is None:
            rgb = VANILLA.get(short)
        if rgb is None:
            # Last resort: guess from the name so leaves stay green.
            rgb = (74, 110, 50) if "leaves" in short or "needles" in short else GREY
        else:
            rgb = _tint(short, rgb)
        self.cache[block] = rgb
        return rgb


def render(voxels, palette, size=128, supersample=2):
    """
    Voxels ((x, y, z) -> blockstate string) -> a PNG byte string, or None.

    Painter's algorithm from an Axiom-ish angle (yaw 135, pitch 30): draw in
    order of increasing x + y + z so nearer blocks land on top.
    """
    if Image is None or not voxels:
        return None

    big = size * supersample
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    ys = [p[1] for p in voxels]
    y0, y1 = min(ys), max(ys)

    # Project first, then fit: a palm and a baobab have wildly different
    # proportions, and deriving the scale from the raw dimensions gets one of
    # them wrong every time.
    def project(p):
        x, y, z = p
        return (x - z), ((x + z) * 0.5 - (y - y0) * 1.2)

    order = sorted(voxels, key=lambda p: p[0] + p[1] + p[2])
    flat = [project(p) for p in order]
    px = [p[0] for p in flat]
    py = [p[1] for p in flat]
    span_x = max(px) - min(px) + 1.0
    span_y = max(py) - min(py) + 1.0

    margin = big * 0.06
    unit = max(1.0, min((big - 2 * margin) / span_x, (big - 2 * margin) / span_y))
    ox = big / 2.0 - (min(px) + max(px)) / 2.0 * unit
    oy = big / 2.0 - (min(py) + max(py)) / 2.0 * unit
    half = max(1.0, unit * 0.62)

    for pos, (fx, fy) in zip(order, flat):
        r, g, b = palette.get(voxels[pos].split("[", 1)[0])
        # Cheap height shading so the silhouette has some depth.
        f = 0.72 + 0.28 * ((pos[1] - y0) / max(1, y1 - y0))
        col = (min(255, int(r * f)), min(255, int(g * f)), min(255, int(b * f)), 255)
        sx = ox + fx * unit
        sy = oy + fy * unit
        draw.rectangle([sx - half, sy - half, sx + half, sy + half], fill=col)

    img = img.resize((size, size), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()
