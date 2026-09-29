package com.example.worldgen;

import com.example.FantasyMapGenerator;
import com.example.terrain.TerrainParams;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;

/**
 * Build-height tiers for blended-terrain worlds. Each tier is a registered
 * dimension type in the mod's datapack ({@code fmg_overworld_h<top>}, plus the
 * original {@code fmg_overworld_tall} for 400); a new world gets the smallest
 * one its mountains fit under. Registered types (rather than a per-world inline
 * one) because the client receives the dimension type by registry id.
 */
public final class FmgHeightTiers {

    /** Exclusive build tops (min Y is -64 for all of them). */
    public static final int[] TOPS = {320, 400, 512, 640, 768, 1024, 1280, 1536, 2032};

    private FmgHeightTiers() {
    }

    /**
     * Smallest tier that fits the terrain the parameters will produce: the macro
     * peak plus ridge relief (~2 std above it) after mountain compression, the
     * bake's top margin, and room above the summits for trees and building.
     */
    public static int topFor(int seaLevel, TerrainParams p) {
        double needed = seaLevel + p.compress(p.peak * (1 + 2 * p.relief)) + p.topMargin + 32;
        for (int top : TOPS) {
            if (top >= needed) return top;
        }
        return TOPS[TOPS.length - 1];
    }

    public static RegistryKey<DimensionType> dimensionType(int top) {
        String path = top == 400 ? "fmg_overworld_tall" : "fmg_overworld_h" + top;
        return RegistryKey.of(RegistryKeys.DIMENSION_TYPE, new Identifier(FantasyMapGenerator.MOD_ID, path));
    }
}
