package com.example.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

/**
 * Keeps a structure from crashing chunk generation on a Conquest Reforged plant.
 *
 * <p>When a structure template is placed during worldgen (ruined portals,
 * shipwrecks, ocean ruins, jigsaw villages...), {@code StructureTemplate.updateCorner}
 * asks the blocks along the structure's outline to update against their new
 * neighbours, on the generation region. Conquest's plants (its {@code Bush} and
 * the classes built like it) apply vanilla's plant rule only while the world is
 * generating -- {@code PlacementHelper.isDuringWorldGen} is just
 * {@code !(view instanceof World)} -- so a plant on ground not tagged
 * {@code #minecraft:dirt} fails it there, vanilla's {@code PlantBlock} answers
 * air, and Conquest then calls {@code .with(LAYERS, ...)} on that air:
 *
 * <pre>
 * IllegalArgumentException: Cannot set property IntProperty{name=layers ...}
 *     as it does not exist in Block{minecraft:air}
 *   at ...plants.Bush.getStateForNeighborUpdate(Bush.java:120)
 *   at StructureTemplate.updateCorner -> RuinedPortalStructurePiece.generate
 * </pre>
 *
 * The layered surface puts plants on Conquest layer blocks (taiga grass, forest
 * floor, sand and gravel layers), most of which are not tagged dirt, so every one
 * of them next to a generating structure could do this. Everywhere else the same
 * update runs on a real {@code World} (chunk post-processing, live neighbour
 * updates, chunk upgrades), where Conquest accepts any ground; this is the only
 * path that reaches it during generation, so the guard sits here rather than in
 * Conquest's own classes -- it needs nothing but Minecraft to compile, and it
 * covers every Conquest plant class with the same pattern, not just {@code Bush}.
 *
 * <p>On that exception the block is kept as it was. It was placed deliberately
 * by the surface pass on ground the live game accepts, and Conquest's own case
 * for a plant that really lost its ground (air below) returns air before it gets
 * as far as the broken call, so that still works.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateNeighborUpdateMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgStructureGuard");
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** Both calls in the {@code updateCorner} lambda: the structure's block and the world's. */
    @WrapOperation(
            method = "method_15173",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;getStateForNeighborUpdate("
                    + "Lnet/minecraft/util/math/Direction;Lnet/minecraft/block/BlockState;"
                    + "Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
    private static BlockState fantasymapgenerator$keepBrokenPlant(BlockState state, Direction direction,
            BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos neighborPos,
            Operation<BlockState> original) {
        try {
            return original.call(state, direction, neighbor, world, pos, neighborPos);
        } catch (IllegalArgumentException e) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("{} at {} failed its neighbour update while a structure was placed ({}); kept it as"
                        + " it was. This is a Conquest Reforged plant bug during worldgen; further ones are"
                        + " not logged.", Registries.BLOCK.getId(state.getBlock()), pos, e.getMessage());
            }
            return state;
        }
    }
}
