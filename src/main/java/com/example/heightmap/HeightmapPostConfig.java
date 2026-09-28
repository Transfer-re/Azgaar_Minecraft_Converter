package com.example.heightmap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Tunables for the FMG generator's terrain post-processing:
 * <ul>
 *   <li>{@code detail} -- fractal noise added to the raw FMG height, per column,
 *       fading in above sea level so coasts and lake floors stay smooth;</li>
 *   <li>{@code rivers} -- the carved, stepped watercourses built by
 *       {@code com.example.fmg.FMGRiverNetwork} and stamped in
 *       {@code ImageMapChunkGenerator} (channel + water + waterfalls + arch
 *       bridges where roads cross).</li>
 * </ul>
 * Written to {@code config/fantasymapgenerator/heightmap.json} on first run;
 * edit and restart to apply.
 */
public final class HeightmapPostConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGHeightmapConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String RELATIVE_PATH = "fantasymapgenerator/heightmap.json";

    /** Master switch for all terrain post-processing in this file. */
    public boolean enabled = true;

    /** 0 = derive deterministically from the FMG map / export name; else this exact value. */
    public long seed = 0L;

    public Detail detail = new Detail();
    public Rivers rivers = new Rivers();
    /** The blended terrain bake (worlds created with the blended terrain type only). */
    public com.example.terrain.TerrainParams terrain = new com.example.terrain.TerrainParams();

    // ---- detail noise ------------------------------------------------

    public static final class Detail {
        public boolean enabled = true;
        public double amplitude = 6.0;      // blocks at full strength
        public double baseFrequency = 1.0 / 90.0;
        public int octaves = 5;
        public double lacunarity = 2.0;
        public double gain = 0.5;
        public double fadeStartAboveSea = 2.0;
        public double fadeEndAboveSea = 28.0;

        /** A {@link HeightPostProcess.FractalDetail} pass configured from these values. */
        public HeightPostProcess.FractalDetail toPass() {
            HeightPostProcess.FractalDetail d = new HeightPostProcess.FractalDetail();
            d.amplitude = amplitude;
            d.baseFrequency = baseFrequency;
            d.octaves = Math.max(1, octaves);
            d.lacunarity = lacunarity;
            d.gain = gain;
            d.fadeStart = fadeStartAboveSea;
            d.fadeEnd = fadeEndAboveSea;
            return d;
        }
    }

    // ---- rivers ----------------------------------------------------

    public static final class Rivers {
        public boolean enabled = true;

        /** Blocks each side of the channel that still count as "river" (water fill, river biome). */
        public int bankWidth = 2;

        // ---- route: snap the spline onto the valley floor -------------
        /**
         * Perpendicular search radius for the lowest ground: each path sample
         * slides sideways onto the valley floor, so the river follows valleys
         * instead of cutting across whatever the spline happened to hit.
         * Endpoints are pinned, so mouth/source stay exactly where Azgaar put them.
         */
        public double valleySnapRadius = 18.0;
        public double valleySnapStep = 3.0;
        /** Cost per block of sideways travel -- keeps the river from drifting on flat ground. */
        public double valleySnapBias = 0.05;
        /** Samples of smoothing applied to the sideways offsets (keeps the path smooth). */
        public int valleySnapSmoothing = 6;

        // ---- meander ---------------------------------------------------
        /** Perpendicular wander on flat ground, in blocks. Fades out on slopes and at both ends. */
        public double meanderAmplitude = 5.0;
        public double meanderWavelength = 140.0;

        // ---- bed profile ------------------------------------------------
        /**
         * The bed is the running minimum of the terrain along the path (so it
         * never runs uphill), then smoothed this many times and re-clamped
         * under the terrain.
         */
        public int bedSmoothingPasses = 4;
        public int bedSmoothingRadius = 5;

        // ---- valley cross-section ---------------------------------------
        /** Blocks the valley floor rises per block away from the channel edge, on gentle ground. */
        public double bankSlope = 0.8;
        /** ...and where the surrounding terrain is steep (keeps gorges narrow instead of huge scars). */
        public double bankSlopeSteep = 1.7;
        /** Bank height above the water at which {@code bankSlopeSteep} is fully applied. */
        public double bankSteepRise = 18.0;
        /** Cut reach beyond the channel edge. The valley stops itself before this where terrain allows. */
        public double maxValleyRadius = 26.0;
        /** Smooth-min softness where the valley meets untouched terrain (blocks). 0 = hard crease. */
        public double valleySmoothK = 3.0;

        // ---- width -------------------------------------------------------
        /** Channel half-width from the Azgaar width, clamped. */
        public double minHalfWidth = 3.0;
        public double maxHalfWidth = 24.0;
        public double kmToBlocks = 2000.0 / 12.0;
        /** How strongly steep stretches narrow the channel (0 = constant width). */
        public double slopeNarrowing = 1.1;
        /** Samples of smoothing on the width profile -- stops the width flickering section to section. */
        public int widthSmoothing = 10;

        // ---- navigable (flat / sea-level) reaches -------------------------
        /**
         * Bed slope at or below which a reach counts as flat: these are the
         * stretches that join oceans and lakes, so they keep the river's full
         * width and a boat-friendly depth instead of being narrowed away.
         */
        public double navigableSlope = 0.02;
        /** A flat reach never drops below this fraction of the river's own widest point. */
        public double navigableWidthContinuity = 0.5;
        public double navigableMinHalfWidth = 4.0;
        public int navigableMinDepth = 3;

        // ---- depth --------------------------------------------------------
        /** Centreline depth: base, + discharge term, + width term; clamped. Bed shelves to ~1 at the banks. */
        public double minDepth = 2.0;
        public double maxDepth = 5.0;
        public double dischargeDepth = 2.5;
        public double widthDepth = 0.3;    // extra centre depth per block of half-width
        /** How much steep (rapid) stretches shallow out. */
        public double slopeShallowing = 1.4;

        // ---- water steps / waterfalls ---------------------------------------
        /**
         * Minecraft water is flat, so a descending river becomes flat pools with
         * drops between them. Drops are 1 block (rapids) until the bed slope
         * passes {@code waterfallSlope}, where they merge into real falls of up
         * to {@code maxWaterfallDrop}.
         */
        public double waterfallSlope = 0.20;
        public int maxWaterfallDrop = 4;
        /** Sub-block jitter on the pool boundaries so step spacing is not perfectly regular. */
        public double stepJitter = 0.45;
        /**
         * Blocks the water surface is kept below the top of the ground beside the
         * river, so a pool never spills over a gentle bank. 0 = off.
         */
        public int bankFreeboard = 1;

        /** Stone-brick arch where a road crosses: rise of the arch and headroom kept under it. */
        public int bridgeArchRise = 4;
        public int bridgeClearance = 2;
        /**
         * Grade (rise per block) of the ramps that lift a road to its bridge deck.
         * The road climbs to deck height before the river instead of dipping into
         * the river's dent and stepping up onto the bridge.
         */
        public double bridgeApproachGrade = 0.33;
    }

    // ---- loading -------------------------------------------------

    private static volatile HeightmapPostConfig cached;

    public static HeightmapPostConfig get() {
        HeightmapPostConfig c = cached;
        if (c == null) {
            synchronized (HeightmapPostConfig.class) {
                if (cached == null) cached = loadOrCreate();
                c = cached;
            }
        }
        return c;
    }

    public static void invalidate() {
        cached = null;
    }

    private static HeightmapPostConfig loadOrCreate() {
        HeightmapPostConfig defaults = new HeightmapPostConfig();
        Path path;
        try {
            path = FabricLoader.getInstance().getConfigDir().resolve(RELATIVE_PATH).normalize();
        } catch (Throwable t) {
            LOGGER.warn("config dir unavailable; using built-in heightmap defaults", t);
            return defaults;
        }
        if (!Files.exists(path)) {
            write(path, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            HeightmapPostConfig loaded = GSON.fromJson(reader, HeightmapPostConfig.class);
            if (loaded == null) return defaults;
            if (loaded.detail == null) loaded.detail = new Detail();
            if (loaded.rivers == null) loaded.rivers = new Rivers();
            if (loaded.terrain == null) loaded.terrain = new com.example.terrain.TerrainParams();
            write(path, loaded); // rewrite so newly-added fields appear in the file
            return loaded;
        } catch (Exception ex) {
            LOGGER.warn("failed to read {} (using defaults)", path, ex);
            return defaults;
        }
    }

    private static void write(Path path, HeightmapPostConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException ex) {
            LOGGER.warn("failed to write heightmap config at {}", path, ex);
        }
    }
}
