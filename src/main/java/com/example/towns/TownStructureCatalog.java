package com.example.towns;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.FantasyMapGenerator;

import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

/**
 * Catalog of premade town-building NBT structures, one folder per biome under
 * {@code data/fantasymapgenerator/structure/towns/<biome>/}.
 *
 * <p>File name convention: {@code <W>_<H>_<doors>[_<variant>].nbt}, e.g.
 * {@code 5_8_e.nbt} or {@code 5_6_n_2.nbt}:
 * <ul>
 *   <li>{@code W}/{@code H} -- the footprint this building is meant to fill
 *       in the settlement grid, W along world X, H along world Z, as
 *       authored (rotation NONE, mirror NONE). The raw NBT bounding box is
 *       usually a block or two bigger than this on each axis (eaves/porch);
 *       that's fine, it's meant to overhang the reserved footprint slightly;</li>
 *   <li>{@code doors} -- one or more of {@code n e s w}, the compass side(s)
 *       that have a door, as authored (verified empirically against the
 *       sample files: local +X = east, +Z = south, matching vanilla
 *       {@link Direction} exactly -- no baked-in inversion);</li>
 *   <li>{@code variant} -- optional trailing number distinguishing multiple
 *       buildings with the same size/doors (e.g. {@code 5_6_n_1.nbt},
 *       {@code 5_6_n_2.nbt}).</li>
 * </ul>
 *
 * <p>Every structure's local Y=0 is authored 3 blocks below its ground floor
 * (a foundation buffer for uneven terrain) -- see {@link TownStructurePlacer}.
 */
public final class TownStructureCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownStructureCatalog");
    private static final String NAMESPACE = FantasyMapGenerator.MOD_ID;
    /** Default resource root; a content profile can point at its own set instead. */
    private static final String DEFAULT_ROOT = "towns/";
    public static final String FALLBACK_BIOME = "plains";
    private static final Pattern FILE_PATTERN = Pattern.compile("(\\d+)_(\\d+)_([nesw]+)(?:_(\\d+))?");
    private static final BlockMirror[] MIRRORS = {BlockMirror.NONE, BlockMirror.FRONT_BACK};
    private static final BlockRotation[] ROTATIONS = BlockRotation.values();

    private static final Map<String, List<Entry>> CACHE = new ConcurrentHashMap<>();

    private TownStructureCatalog() {
    }

    /** One available building, as authored (rotation NONE, mirror NONE). */
    public record Entry(Identifier id, int width, int height, Set<Direction> doors) {
    }

    /** A chosen building plus the transform needed to fit the target footprint/door. */
    public record Pick(Identifier id, BlockRotation rotation, BlockMirror mirror) {
    }

    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * All catalog entries for {@code biomeKey} (e.g. {@code "desert"}),
     * falling back to {@value #FALLBACK_BIOME} if that folder is empty or
     * doesn't exist -- multiple biomes can be added later just by adding more
     * {@code towns/<biome>/} folders.
     */
    public static List<Entry> forBiome(MinecraftServer server, String biomeKey) {
        return forBiome(server, biomeKey, DEFAULT_ROOT);
    }

    /** As {@link #forBiome(MinecraftServer, String)}, but from a profile's own structure root. */
    public static List<Entry> forBiome(MinecraftServer server, String biomeKey, String root) {
        String prefix = (root == null || root.isBlank()) ? DEFAULT_ROOT : root;
        if (!prefix.endsWith("/")) prefix = prefix + "/";
        ROOT_OVERRIDE.set(prefix);
        try {
            return forBiomeResolved(server, biomeKey);
        } finally {
            ROOT_OVERRIDE.remove();
        }
    }

    private static final ThreadLocal<String> ROOT_OVERRIDE = new ThreadLocal<>();

    private static String root() {
        String r = ROOT_OVERRIDE.get();
        return r == null ? DEFAULT_ROOT : r;
    }

    private static List<Entry> forBiomeResolved(MinecraftServer server, String biomeKey) {
        List<Entry> entries = forBiomeExact(server, biomeKey);
        if (!entries.isEmpty() || FALLBACK_BIOME.equals(normalize(biomeKey))) {
            return entries;
        }
        return forBiomeExact(server, FALLBACK_BIOME);
    }

    private static List<Entry> forBiomeExact(MinecraftServer server, String biomeKey) {
        return CACHE.computeIfAbsent(root() + normalize(biomeKey), key -> scan(server, normalize(biomeKey)));
    }

    private static String normalize(String biomeKey) {
        return (biomeKey == null || biomeKey.isBlank()) ? FALLBACK_BIOME : biomeKey.toLowerCase(Locale.ROOT);
    }

    private static List<Entry> scan(MinecraftServer server, String biomeKey) {
        String prefix = root() + biomeKey + "/";
        StructureTemplateManager manager = server.getStructureTemplateManager();
        List<Entry> out = new ArrayList<>();
        manager.streamTemplates()
                .filter(id -> id.getNamespace().equals(NAMESPACE) && id.getPath().startsWith(prefix))
                .forEach(id -> {
                    Entry e = parse(id, prefix);
                    if (e != null) {
                        out.add(e);
                    }
                });
        LOGGER.info("Town structure catalog[{}]: {} building(s)", biomeKey, out.size());
        return out;
    }

    private static Entry parse(Identifier id, String prefix) {
        String fileName = id.getPath().substring(prefix.length());
        Matcher m = FILE_PATTERN.matcher(fileName);
        if (!m.matches()) {
            LOGGER.warn("Ignoring town structure with unrecognised name: {}", id);
            return null;
        }
        int w = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        Set<Direction> doors = EnumSet.noneOf(Direction.class);
        for (char c : m.group(3).toCharArray()) {
            Direction d = letterToDirection(c);
            if (d != null) {
                doors.add(d);
            }
        }
        if (doors.isEmpty()) {
            LOGGER.warn("Town structure {} has no recognised door letters, skipping", id);
            return null;
        }
        return new Entry(id, w, h, doors);
    }

    private static Direction letterToDirection(char c) {
        return switch (c) {
            case 'n' -> Direction.NORTH;
            case 'e' -> Direction.EAST;
            case 's' -> Direction.SOUTH;
            case 'w' -> Direction.WEST;
            default -> null;
        };
    }

    /**
     * Picks a random building (+ rotation/mirror) whose authored footprint fits
     * {@code wantWidth}x{@code wantHeight} (world X by world Z) with a door
     * facing {@code requiredDoor} once that rotation/mirror is applied.
     *
     * @return {@code null} if nothing in {@code entries} can satisfy both
     */
    public static Pick pickFor(List<Entry> entries, int wantWidth, int wantHeight,
            Direction requiredDoor, Random random) {
        List<Pick> matches = new ArrayList<>();
        for (Entry e : entries) {
            for (BlockMirror mirror : MIRRORS) {
                for (BlockRotation rotation : ROTATIONS) {
                    boolean swapped = rotation == BlockRotation.CLOCKWISE_90
                            || rotation == BlockRotation.COUNTERCLOCKWISE_90;
                    int fw = swapped ? e.height() : e.width();
                    int fh = swapped ? e.width() : e.height();
                    if (fw != wantWidth || fh != wantHeight) {
                        continue;
                    }
                    boolean hasDoor = false;
                    for (Direction d : e.doors()) {
                        if (rotation.rotate(mirror.apply(d)) == requiredDoor) {
                            hasDoor = true;
                            break;
                        }
                    }
                    if (hasDoor) {
                        matches.add(new Pick(e.id(), rotation, mirror));
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(random.nextInt(matches.size()));
    }
}
