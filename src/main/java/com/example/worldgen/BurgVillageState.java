package com.example.worldgen;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.world.PersistentState;

/**
 * Tracks which FMG burg ids have already had a village placed.
 */
final class BurgVillageState extends PersistentState {

    private static final String NBT_KEY = "spawnedBurgs";

    private final Set<Integer> spawnedBurgIds = new HashSet<>();

    static BurgVillageState fromNbt(NbtCompound nbt) {
        BurgVillageState state = new BurgVillageState();
        if (nbt.contains(NBT_KEY)) {
            int[] ids = nbt.getIntArray(NBT_KEY);
            for (int id : ids) {
                state.spawnedBurgIds.add(id);
            }
        }
        return state;
    }

    boolean isSpawned(int burgId) {
        return spawnedBurgIds.contains(burgId);
    }

    void markSpawned(int burgId) {
        if (spawnedBurgIds.add(burgId)) {
            markDirty();
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        int[] ids = spawnedBurgIds.stream().mapToInt(Integer::intValue).toArray();
        nbt.put(NBT_KEY, new NbtIntArray(ids));
        return nbt;
    }
}
