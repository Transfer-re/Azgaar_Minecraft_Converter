package com.example.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.example.worldgen.FmgStructureBiomes;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

/**
 * Lets every structure start in the FMG biomes that stand in for its own
 * (see {@link FmgStructureBiomes}). {@code getValidBiomes} is the only way
 * Minecraft reads a structure's biome list: the structure-set filter in
 * {@code StructurePlacementCalculator}, the start check in
 * {@code ChunkGenerator.trySetStructureStart}, and {@code StructureLocator}
 * behind {@code /locate} and explorer maps.
 */
@Mixin(Structure.class)
public abstract class StructureValidBiomesMixin {

    @ModifyReturnValue(method = "getValidBiomes", at = @At("RETURN"))
    private RegistryEntryList<Biome> fmg$widenWithStandIns(RegistryEntryList<Biome> biomes) {
        return FmgStructureBiomes.widen(biomes);
    }
}
