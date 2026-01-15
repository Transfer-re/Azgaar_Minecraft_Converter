package com.example.worldgen;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;

public final class FilteringRegistryWrapper<T> implements RegistryWrapper<T> {

    private final RegistryWrapper<T> delegate;
    private final Predicate<RegistryKey<T>> excludedKeys;

    public FilteringRegistryWrapper(RegistryWrapper<T> delegate, Predicate<RegistryKey<T>> excludedKeys) {
        this.delegate = delegate;
        this.excludedKeys = excludedKeys;
    }

    public static <T> FilteringRegistryWrapper<T> excludingKey(RegistryWrapper<T> delegate, RegistryKey<T> excludedKey) {
        return new FilteringRegistryWrapper<>(delegate, excludedKey::equals);
    }

    @Override
    public Stream<RegistryEntry.Reference<T>> streamEntries() {
        return delegate.streamEntries().filter(entry -> !excludedKeys.test(entry.registryKey()));
    }

    @Override
    public Stream<RegistryEntryList.Named<T>> getTags() {
        return delegate.getTags();
    }

    @Override
    public Optional<RegistryEntry.Reference<T>> getOptional(RegistryKey<T> key) {
        if (excludedKeys.test(key)) {
            return Optional.empty();
        }
        return delegate.getOptional(key);
    }

    @Override
    public Optional<RegistryEntryList.Named<T>> getOptional(TagKey<T> tag) {
        return delegate.getOptional(tag);
    }
}
