package com.example.profile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the content profiles in {@code config/fantasymapgenerator/profiles/}.
 *
 * <p>The built-in {@code vanilla} and {@code conquest} profiles are written out
 * on first run so they can be edited in place; any other {@code *.json} dropped
 * in that folder is picked up as an extra profile. A built-in file whose
 * {@code version} is older than the mod's is replaced (the old one is kept as
 * {@code <id>.json.v<N>.bak}). A profile whose {@code requiresMod} is not
 * installed is loaded but not offered, so the same jar runs with or without the
 * mod it targets; {@code -Dfmg.allProfiles=true} offers them anyway, for testing
 * (missing blocks then fall back to vanilla stand-ins).
 */
public final class FmgProfiles {

    private static final Logger LOGGER = LoggerFactory.getLogger("FmgProfiles");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String RELATIVE_DIR = "fantasymapgenerator/profiles";

    private static volatile Map<String, FmgContentProfile> cached;

    private FmgProfiles() {
    }

    /** The profile with this id, or the vanilla profile when it is missing or unusable. */
    public static FmgContentProfile get(String id) {
        Map<String, FmgContentProfile> all = all();
        FmgContentProfile p = all.get(id == null || id.isBlank() ? FmgContentProfile.VANILLA_ID : id);
        if (p != null) {
            return p;
        }
        LOGGER.warn("Unknown FMG content profile '{}'; falling back to '{}'", id, FmgContentProfile.VANILLA_ID);
        FmgContentProfile fallback = all.get(FmgContentProfile.VANILLA_ID);
        return fallback != null ? fallback : FmgContentProfile.vanilla();
    }

    /** Profiles whose required mod (if any) is installed, in file order with vanilla first. */
    public static List<FmgContentProfile> available() {
        List<FmgContentProfile> out = new ArrayList<>();
        boolean all = Boolean.getBoolean("fmg.allProfiles");
        for (FmgContentProfile p : all().values()) {
            if (!all && p.requiresMod != null && !p.requiresMod.isBlank()
                    && !FabricLoader.getInstance().isModLoaded(p.requiresMod)) {
                continue;
            }
            out.add(p);
        }
        if (out.isEmpty()) {
            out.add(FmgContentProfile.vanilla());
        }
        return out;
    }

    /** Every loaded profile, offered or not (a world may use one whose mod is missing). */
    public static java.util.Collection<FmgContentProfile> loaded() {
        return all().values();
    }

    public static void invalidate() {
        cached = null;
    }

    private static Map<String, FmgContentProfile> all() {
        Map<String, FmgContentProfile> c = cached;
        if (c == null) {
            synchronized (FmgProfiles.class) {
                if (cached == null) cached = loadAll();
                c = cached;
            }
        }
        return c;
    }

    private static Map<String, FmgContentProfile> loadAll() {
        Map<String, FmgContentProfile> out = new LinkedHashMap<>();
        FmgContentProfile vanilla = FmgContentProfile.vanilla();
        out.put(vanilla.id, vanilla);

        Path dir;
        try {
            dir = FabricLoader.getInstance().getConfigDir().resolve(RELATIVE_DIR).normalize();
        } catch (Throwable t) {
            LOGGER.warn("config dir unavailable; using the built-in vanilla profile only", t);
            return out;
        }

        try {
            Files.createDirectories(dir);
            writeIfOutdated(dir.resolve(vanilla.id + ".json"), vanilla);
            writeIfOutdated(dir.resolve("conquest.json"), FmgContentProfile.conquest());
        } catch (IOException ex) {
            LOGGER.warn("could not write default FMG profiles to {}", dir, ex);
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                FmgContentProfile loaded = read(file);
                if (loaded != null) {
                    out.put(loaded.id, loaded);
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("could not list FMG profiles in {}", dir, ex);
        }

        LOGGER.info("FMG content profiles: {}", out.keySet());
        return out;
    }

    private static FmgContentProfile read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            FmgContentProfile loaded = GSON.fromJson(reader, FmgContentProfile.class);
            if (loaded == null) {
                return null;
            }
            if (loaded.id == null || loaded.id.isBlank()) {
                String name = file.getFileName().toString();
                loaded.id = name.substring(0, name.length() - ".json".length());
            }
            loaded.sanitize();
            return loaded;
        } catch (Exception ex) {
            LOGGER.warn("failed to read FMG profile {} (skipping)", file, ex);
            return null;
        }
    }

    /** Writes a built-in profile if it is missing, or older than the built-in revision. */
    private static void writeIfOutdated(Path file, FmgContentProfile profile) throws IOException {
        if (Files.exists(file)) {
            FmgContentProfile existing = read(file);
            int onDisk = existing == null ? -1 : existing.version;
            if (onDisk >= profile.version) {
                return;
            }
            Path backup = file.resolveSibling(file.getFileName() + ".v" + Math.max(0, onDisk) + ".bak");
            Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("FMG profile {} is revision {}, the mod ships {}: replacing it (old copy at {})",
                    file.getFileName(), onDisk, profile.version, backup.getFileName());
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(profile, writer);
        }
        LOGGER.info("wrote default FMG profile {}", file);
    }
}
