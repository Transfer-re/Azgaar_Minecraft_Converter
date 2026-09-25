package com.example.towns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.processor.BlockIgnoreStructureProcessor;
import net.minecraft.structure.processor.StructureProcessor;
import net.minecraft.structure.processor.StructureProcessorType;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldView;

/**
 * Places one {@link TownStructureCatalog} building into the world.
 *
 * <p>Every structure is authored unrotated with local (0,0,0) at one bottom
 * corner of its bounding box (verified empirically: +local X = east,
 * +local Z = south, so that corner is the structure's west/north-bottom
 * corner) and its ground floor exactly 3 blocks above that -- a foundation
 * buffer so it can be dug a little into a slope. Placement:
 * <ol>
 *   <li>Y anchor = the terrain height at the door, minus 3;</li>
 *   <li>X/Z anchor = wherever local (0,0,0) needs to land so that, after the
 *       chosen rotation/mirror, the structure's transformed bounding box's
 *       minimum-X/minimum-Z corner coincides with the target rect's
 *       minimum-X/minimum-Z corner. Rotation/mirror pivot around local
 *       (0,0,0), which is a fixed point of that transform, so this is just
 *       "translate until the transformed min corner lines up".</li>
 * </ol>
 */
public final class TownStructurePlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownStructurePlacer");

    private TownStructurePlacer() {
    }

    /**
     * @param rectMinWorldX world X of the reserved footprint's minimum corner
     * @param rectMinWorldZ world Z of the reserved footprint's minimum corner
     * @param doorGroundWorldY terrain height at the door (the block the door will stand on)
     */
    public static boolean place(ServerWorld world, Identifier id, BlockRotation rotation, BlockMirror mirror,
            int rectMinWorldX, int rectMinWorldZ, int doorGroundWorldY, Random random) {
        StructureTemplate template = world.getServer().getStructureTemplateManager().getTemplate(id).orElse(null);
        if (template == null) {
            LOGGER.warn("Town structure {} failed to load", id);
            return false;
        }

        Vec3i size = template.getSize();
        BlockPos maxCorner = new BlockPos(Math.max(0, size.getX() - 1), 0, Math.max(0, size.getZ() - 1));
        BlockPos originCorner = StructureTemplate.transformAround(BlockPos.ORIGIN, mirror, rotation, BlockPos.ORIGIN);
        BlockPos farCorner = StructureTemplate.transformAround(maxCorner, mirror, rotation, BlockPos.ORIGIN);
        int minXOffset = Math.min(originCorner.getX(), farCorner.getX());
        int minZOffset = Math.min(originCorner.getZ(), farCorner.getZ());

        BlockPos pos = new BlockPos(
                rectMinWorldX - minXOffset,
                doorGroundWorldY - 3,
                rectMinWorldZ - minZOffset);

        StructurePlacementData data = new StructurePlacementData()
                .setRotation(rotation)
                .setMirror(mirror)
                .setIgnoreEntities(false)
                .setUpdateNeighbors(true)
                // these samples were captured with the structure block still inside
                // the box (leaves a real minecraft:structure_block -- and its
                // permanent debug bounding-box overlay -- in the world otherwise)
                .addProcessor(BlockIgnoreStructureProcessor.IGNORE_STRUCTURE_BLOCKS)
                // the signs themselves are decoration and should still place --
                // it's just their captured text NBT that doesn't decode cleanly on
                // this game version (spammed "Failed to parse either / not a list
                // of 4 elements", a sign-text codec mismatch from whatever newer MC
                // version these were authored in). Drop the NBT so a fresh blank
                // sign spawns instead of the broken captured one.
                .addProcessor(DropSignNbtProcessor.INSTANCE);

        return template.place(world, pos, pos, data, random, Block.NOTIFY_ALL);
    }

    private static final class DropSignNbtProcessor extends StructureProcessor {
        static final DropSignNbtProcessor INSTANCE = new DropSignNbtProcessor();

        @Override
        public StructureTemplate.StructureBlockInfo process(WorldView world, BlockPos pos, BlockPos pivot,
                StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current,
                StructurePlacementData data) {
            if (current != null && current.nbt() != null && current.state().isIn(BlockTags.ALL_SIGNS)) {
                return new StructureTemplate.StructureBlockInfo(current.pos(), current.state(), null);
            }
            return current;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            // never (de)serialised -- this processor only ever runs in-memory.
            return null;
        }
    }
}
