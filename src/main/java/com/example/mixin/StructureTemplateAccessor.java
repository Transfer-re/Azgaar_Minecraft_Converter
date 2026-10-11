package com.example.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.structure.StructureTemplate;

/**
 * Read access to a loaded structure template's blocks. The tree feature loads
 * its objects through the vanilla template manager (datapack overrides,
 * caching, data fixing all behave as for any structure) but pastes them itself,
 * so it needs the raw block list rather than {@code StructureTemplate.place}.
 */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {

    @Accessor("blockInfoLists")
    List<StructureTemplate.PalettedBlockInfoList> fmg$blockInfoLists();
}
