"""
Survey the Axiom blueprint tree pack: which blocks each species is built from,
in what proportion, and what structural role each one is playing.

    python -m fmgtrees.survey_axiom <blueprints-dir> [--out <report-dir>]

The point of this is that the pack does not use blocks literally. A "douglas
fir" is made of acacia_wood, acacia_leaves and spruce_slab; several species use
wheat, horn_coral and horn_coral_fan as foliage and walls/fences/trapdoors as
branches. So a name-based remap (spruce_log -> norway_spruce_log) would be
nonsense -- the remap has to be written per species, per role.

The role guess here is mechanical, from the block family:

    trunk    full log/wood blocks           -> conquest:<species>_log
    branch   fence / wall / 5-way connectors -> conquest:<species>_log_branch
    limb     slab / stair / trapdoor        -> the matching CR log sub-shape
    foliage  leaves                         -> conquest:<species>_leaves
    filler   plants, coral, misc            -> NEEDS A HUMAN

Everything landing in `filler` is what actually needs eyes on it. The report
writes both a readable .md and a roles.json to be edited and fed to the remap.
"""

import argparse
import collections
import glob
import json
import os
import sys

from .axiom import Blueprint

TRUNK = ("_log", "_wood", "_stem", "_hyphae")
FOLIAGE = ("_leaves",)
BRANCH = ("_fence", "_fence_gate", "_wall", "_pane", "_bars")
LIMB = ("_slab", "_stairs", "_trapdoor", "_button", "_carpet")


def role_of(name):
    short = name.split(":", 1)[-1]
    if any(short.endswith(sfx) for sfx in FOLIAGE):
        return "foliage"
    if any(short.endswith(sfx) for sfx in BRANCH):
        return "branch"
    if any(short.endswith(sfx) for sfx in LIMB):
        return "limb"
    if any(short.endswith(sfx) for sfx in TRUNK):
        return "trunk"
    return "filler"


def main(argv=None):
    ap = argparse.ArgumentParser(prog="fmgtrees.survey_axiom")
    ap.add_argument("source", help="the Axiom blueprints directory")
    ap.add_argument("--out", default=os.path.join("..", "docs", "conquest"))
    args = ap.parse_args(argv)

    species = sorted(d for d in os.listdir(args.source)
                     if os.path.isdir(os.path.join(args.source, d)))
    os.makedirs(args.out, exist_ok=True)

    report = {}
    failures = []
    for sp in species:
        counts = collections.Counter()
        files = sorted(glob.glob(os.path.join(args.source, sp, "*.bp")))
        sizes = collections.Counter()
        for path in files:
            stem = os.path.basename(path)[:-len(".bp")]
            parts = stem.split("_")
            sizes[parts[-2] if len(parts) >= 2 else "?"] += 1
            try:
                counts.update(Blueprint(path).block_counts())
            except Exception as ex:
                failures.append((os.path.basename(path), str(ex)))

        by_block = collections.Counter()
        for state, n in counts.items():
            by_block[state.split("[")[0]] += n
        total = sum(by_block.values()) or 1

        blocks = []
        for name, n in by_block.most_common():
            blocks.append({
                "block": name,
                "count": n,
                "share": round(100.0 * n / total, 1),
                "role": role_of(name),
                "conquest": "",          # to be filled in by hand
            })
        report[sp] = {
            "blueprints": len(files),
            "sizes": dict(sizes),
            "voxels": total,
            "blocks": blocks,
        }

    with open(os.path.join(args.out, "axiom_roles.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)

    lines = ["# Axiom tree pack — block roles", "",
             "Auto-surveyed. `role` is a mechanical guess from the block family;",
             "`conquest` is blank and needs filling in by hand. Anything with role",
             "`filler` is a stand-in block whose real job can only be seen in game.",
             ""]
    for sp in species:
        r = report[sp]
        sizes = " ".join("%s:%d" % (k, v) for k, v in sorted(r["sizes"].items()))
        lines.append("## %s" % sp)
        lines.append("")
        lines.append("%d blueprints (%s), %d voxels" % (r["blueprints"], sizes, r["voxels"]))
        lines.append("")
        lines.append("| block | share | count | role |")
        lines.append("|---|---|---|---|")
        for b in r["blocks"]:
            if b["share"] < 0.5:
                continue
            lines.append("| `%s` | %.1f%% | %d | %s |"
                         % (b["block"].replace("minecraft:", ""), b["share"],
                            b["count"], b["role"]))
        lines.append("")

    with open(os.path.join(args.out, "axiom_roles.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    roles = collections.Counter()
    for sp in species:
        for b in report[sp]["blocks"]:
            roles[b["role"]] += 1
    print("surveyed %d species, %d blueprints"
          % (len(species), sum(r["blueprints"] for r in report.values())))
    print("palette entries by role: " + ", ".join("%s=%d" % kv for kv in roles.most_common()))
    print("wrote axiom_roles.json and axiom_roles.md to %s" % args.out)

    worst = sorted(species,
                   key=lambda s: -sum(b["share"] for b in report[s]["blocks"]
                                      if b["role"] == "filler"))
    print("\nspecies most dependent on stand-in blocks:")
    for sp in worst[:10]:
        share = sum(b["share"] for b in report[sp]["blocks"] if b["role"] == "filler")
        names = [b["block"].replace("minecraft:", "") for b in report[sp]["blocks"]
                 if b["role"] == "filler" and b["share"] >= 3]
        print("  %-22s %5.1f%% filler   %s" % (sp, share, ", ".join(names[:5])))

    if failures:
        print("\n%d file(s) failed:" % len(failures))
        for name, why in failures[:10]:
            print("  %s: %s" % (name, why))
    return 0


if __name__ == "__main__":
    sys.exit(main())
