package com.example.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseParametersKeys;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * Restores vanilla's 1.18+ noise-cave carving (cheese / spaghetti / noodle /
 * pillars + cave entrances) in the FMG chunk generator.
 *
 * <p>The FMG generator overrides both {@code initialDensity} and
 * {@code finalDensity} of the noise router with {@link ImageMapChunkGenerator}'s
 * height-cap step ({@code (surfaceY - y) * 64}) -- the step locks the surface to
 * the heightmap but has no 3D structure, so nothing below ground gets carved by
 * the density fill (only the legacy carvers make caves).</p>
 *
 * <p>This rebuilds the exact {@code final_density} from the FMG noise settings
 * (vanilla's, verbatim -- range_choice over sloped_cheese, cave functions, both
 * surface slides, blend + interpolate + {@code *0.64} + squeeze, {@code min}
 * noodle) but replaces every {@code sloped_cheese} reference with a scaled proxy
 * of the FMG step, so the cave functions sit at the right depth relative to the
 * FMG surface instead of vanilla's. {@code initialDensity} stays the raw step.</p>
 *
 * <p>The rebuilt router is handed to {@link NoiseConfig#create} so the density
 * functions get their noise samplers bound / holders resolved exactly the way
 * the game does it; the resolved router is then swapped into the live config.</p>
 */
public final class FmgNoiseCaves {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgNoiseCaves");

    private FmgNoiseCaves() {
    }

    private static RegistryKey<DensityFunction> df(String path) {
        return RegistryKey.of(RegistryKeys.DENSITY_FUNCTION, Identifier.ofVanilla(path));
    }

    private static final RegistryKey<DensityFunction> ENTRANCES = df("overworld/caves/entrances");
    private static final RegistryKey<DensityFunction> NOODLE = df("overworld/caves/noodle");
    private static final RegistryKey<DensityFunction> PILLARS = df("overworld/caves/pillars");
    private static final RegistryKey<DensityFunction> SPAGHETTI_2D = df("overworld/caves/spaghetti_2d");
    private static final RegistryKey<DensityFunction> SPAGHETTI_ROUGHNESS =
            df("overworld/caves/spaghetti_roughness_function");

    /** Populated at {@code SERVER_STARTING}, before any chunk is generated. */
    private static volatile RegistryEntryLookup<DensityFunction> densityFunctions;
    private static volatile RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters;

    public static void capture(DynamicRegistryManager registries) {
        try {
            densityFunctions = registries.getWrapperOrThrow(RegistryKeys.DENSITY_FUNCTION);
            noiseParameters = registries.getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not capture worldgen registries; FMG noise caves stay disabled", e);
        }
    }

    public static boolean ready() {
        return densityFunctions != null && noiseParameters != null;
    }

    /**
     * @return a fully resolved noise router = FMG height step + vanilla noise
     *         caves, or {@code null} if the registries aren't captured yet or
     *         anything goes wrong (caller keeps the cave-less step in that case).
     */
    public static NoiseRouter buildResolvedRouter(ChunkGeneratorSettings vanillaBase,
            net.minecraft.world.gen.chunk.GenerationShapeConfig shape,
            DensityFunction fmgStep, int seaLevel, long seed) {
        if (!ready()) {
            return null;
        }
        try {
            NoiseRouter raw = vanillaBase.noiseRouter();
            NoiseRouter fmgRaw = new NoiseRouter(
                    raw.barrierNoise(),
                    raw.fluidLevelFloodednessNoise(),
                    raw.fluidLevelSpreadNoise(),
                    raw.lavaNoise(),
                    raw.temperature(),
                    raw.vegetation(),
                    raw.continents(),
                    raw.erosion(),
                    raw.depth(),
                    raw.ridges(),
                    fmgStep,                       // initialDensity -- surface stays locked to the FMG map
                    buildFinalDensity(fmgStep),    // finalDensity   -- step + vanilla noise caves
                    raw.veinToggle(),
                    raw.veinRidged(),
                    raw.veinGap());

            @SuppressWarnings("deprecation")
            ChunkGeneratorSettings fmgSettings = new ChunkGeneratorSettings(
                    shape,
                    vanillaBase.defaultBlock(),
                    vanillaBase.defaultFluid(),
                    fmgRaw,
                    vanillaBase.surfaceRule(),
                    vanillaBase.spawnTarget(),
                    seaLevel,
                    vanillaBase.mobGenerationDisabled(),
                    false,   // aquifers off -- see ImageMapChunkGenerator (oceans full to shore)
                    false,
                    vanillaBase.usesLegacyRandom());

            return NoiseConfig.create(fmgSettings, noiseParameters, seed).getNoiseRouter();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to build FMG noise caves; keeping plain heightmap terrain", e);
            return null;
        }
    }

    /**
     * finalDensity = {@code min(fmgStep, min(caveField, noodle))}.
     *
     * <p>{@code caveField} is vanilla {@code minecraft:overworld}'s cave carving
     * (cheese / spaghetti / pillars / entrances + both surface slides + blend /
     * interpolate / {@code *0.64} / squeeze) with the terrain-defining
     * {@code sloped_cheese} taken out: the near-surface branch is
     * {@code min(4, 5*entrances)} (only cave entrances cut in), the band selector
     * is the raw FMG step, and the deep cheese term is a small constant. So
     * {@code caveField} never adds terrain -- it only ever goes negative inside a
     * cave. The crisp, per-block {@code fmgStep} alone sets the surface, which
     * keeps the interpolated cave field from terracing the coastline.</p>
     */
    private static DensityFunction buildFinalDensity(DensityFunction fmgStep) {
        RegistryEntryLookup<DensityFunction> dl = densityFunctions;
        RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> nl = noiseParameters;

        DensityFunction entrances = dl.getOrThrow(ENTRANCES).value();
        DensityFunction noodle = dl.getOrThrow(NOODLE).value();
        DensityFunction pillars = dl.getOrThrow(PILLARS).value();
        DensityFunction spaghetti2d = dl.getOrThrow(SPAGHETTI_2D).value();
        DensityFunction spaghettiRoughness = dl.getOrThrow(SPAGHETTI_ROUGHNESS).value();

        DensityFunction caveLayer = DensityFunctionTypes.mul(
                DensityFunctionTypes.constant(4.0),
                DensityFunctionTypes.noise(nl.getOrThrow(NoiseParametersKeys.CAVE_LAYER), 1.0, 8.0).square());
        DensityFunction caveCheese = DensityFunctionTypes.add(
                DensityFunctionTypes.add(DensityFunctionTypes.constant(0.27),
                        DensityFunctionTypes.noise(nl.getOrThrow(NoiseParametersKeys.CAVE_CHEESE),
                                1.0, 2.0 / 3.0)).clamp(-1.0, 1.0),
                DensityFunctionTypes.constant(0.1));   // vanilla: clamp(1.5 - 0.64*sloped_cheese, 0, 0.5), ~0.1 deep
        DensityFunction cheeseCaves = DensityFunctionTypes.add(caveLayer, caveCheese);
        DensityFunction caves = DensityFunctionTypes.min(
                DensityFunctionTypes.min(cheeseCaves, entrances),
                DensityFunctionTypes.add(spaghetti2d, spaghettiRoughness));
        DensityFunction pillarsFiltered = DensityFunctionTypes.rangeChoice(
                pillars, -1_000_000.0, 0.03, DensityFunctionTypes.constant(-1_000_000.0), pillars);
        DensityFunction deepCaves = DensityFunctionTypes.max(caves, pillarsFiltered);

        // Near the surface (top ~25 blocks below it): only cave entrances carve.
        DensityFunction nearSurface = DensityFunctionTypes.min(DensityFunctionTypes.constant(4.0),
                DensityFunctionTypes.mul(DensityFunctionTypes.constant(5.0), entrances));
        // Band selector = the raw step (fine, per-block): 25 blocks ~= 1600.
        DensityFunction carved = DensityFunctionTypes.rangeChoice(
                fmgStep, -1_000_000.0, 1600.0, nearSurface, deepCaves);

        // Bottom slide only (fade toward solid over y -64..-40). Vanilla's TOP
        // slide is intentionally dropped: it forces density to air above y 256,
        // which would decapitate any FMG terrain above that height. The FMG step
        // already produces air above the surface, so no top fade is needed.
        DensityFunction slid = DensityFunctionTypes.lerp(
                DensityFunctionTypes.yClampedGradient(-64, -40, 0.0, 1.0), 0.1171875, carved);

        DensityFunction blended = DensityFunctionTypes.interpolated(DensityFunctionTypes.blendDensity(slid));
        DensityFunction caveField = DensityFunctionTypes.mul(
                DensityFunctionTypes.constant(0.64), blended).squeeze();

        return DensityFunctionTypes.min(fmgStep, DensityFunctionTypes.min(caveField, noodle));
    }
}
