package com.example.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

@Mixin(NoiseConfig.class)
public interface NoiseConfigAccessor {

    @Accessor("noiseRouter")
    NoiseRouter fantasymapgenerator$getNoiseRouter();

    @Mutable
    @Accessor("noiseRouter")
    void fantasymapgenerator$setNoiseRouter(NoiseRouter router);

    @Accessor("noiseParametersRegistry")
    RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> fantasymapgenerator$getNoiseParameters();
}
