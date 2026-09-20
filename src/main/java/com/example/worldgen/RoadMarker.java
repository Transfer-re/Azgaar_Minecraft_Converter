package com.example.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;

/**
 * Invisible block laid one above every generated road/trail/bridge column.
 *
 * <p>Two jobs: (1) it is non-air, so {@code Biome.canSetSnow} (which only
 * accepts air or an existing snow layer) can never put snow on the road; and
 * (2) it is the one unambiguous "the road generator made this" signal -- path
 * blocks, gravel, cobble etc. also occur naturally, but a light block never
 * generates in vanilla worldgen -- so the town sampler detects roads by it.
 *
 * <p>Level 0 emits no light. The block has no solid collision shape, so it
 * does not affect OCEAN_FLOOR / MOTION_BLOCKING heightmaps, and it is
 * invisible unless a player holds a light item.
 */
public final class RoadMarker {

    private RoadMarker() {
    }

    public static BlockState state() {
        return Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 0);
    }

    public static boolean is(BlockState state) {
        return state.isOf(Blocks.LIGHT) && state.get(LightBlock.LEVEL_15) == 0;
    }
}
