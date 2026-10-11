package com.example.client.screen;

import java.nio.file.Path;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import com.example.FantasyMapGenerator;
import com.example.heightmap.HeightmapPostConfig;
import com.example.worldgen.ColorMapBiomeSource;
import com.example.worldgen.FmgHeightTiers;
import com.example.worldgen.ImageMapChunkGenerator;
import com.example.worldmap.MapInfo;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;

/**
 * "Customize" screen for the Fantasy Map world type: choose the FMG JSON export,
 * the pixel-to-block scale, and a couple of terrain/biome toggles. On Done it
 * rewrites the overworld generator via {@code WorldCreator.applyModifier}; the
 * chosen {@link MapInfo} is serialised into {@code level.dat}.
 */
public final class FmgWorldCreateScreen extends Screen {

    public static final RegistryKey<net.minecraft.world.gen.WorldPreset> FMG_PRESET_KEY =
            RegistryKey.of(RegistryKeys.WORLD_PRESET,
                    Identifier.of(FantasyMapGenerator.MOD_ID, "fmg_test"));

    // Legacy terrain keeps the bundled fmg_overworld_tall range; blended terrain
    // picks a taller dimension type from FmgHeightTiers.
    private static final int MIN_Y = -64;
    private static final int LEGACY_MAX_Y = 400;
    private static final int SEA_LEVEL = 63;

    private static final List<Double> SCALES = List.of(4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 48.0);
    private static final List<Double> AMPLITUDES = List.of(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 14.0);

    // Remembered across screen opens in one session.
    private static String sJson;
    private static Double sScale;
    private static Boolean sDetail;
    private static Double sAmplitude;
    private static Boolean sRichBiomes;
    private static String sProfile;
    private static Boolean sBlend;

    private final CreateWorldScreen parent;

    private String jsonPath = sJson;
    private double scale = sScale != null ? sScale : nearest(SCALES, MapInfo.DEFAULT_SCALE);
    private boolean detail = sDetail != null ? sDetail : HeightmapPostConfig.get().detail.enabled;
    private double amplitude = sAmplitude != null ? sAmplitude
            : nearest(AMPLITUDES, HeightmapPostConfig.get().detail.amplitude);
    private boolean richBiomes = sRichBiomes != null ? sRichBiomes : true;
    private boolean blend = sBlend != null ? sBlend : true;

    private final List<com.example.profile.FmgContentProfile> profiles =
            com.example.profile.FmgProfiles.available();
    private com.example.profile.FmgContentProfile profile = pickProfile(sProfile);

    private ButtonWidget mapButton;
    private ButtonWidget doneButton;

    public FmgWorldCreateScreen(CreateWorldScreen parent, GeneratorOptionsHolder holder) {
        super(Text.literal("Fantasy Map settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 310;
        int x = cx - w / 2;
        int y = Math.max(40, this.height / 6);
        int gap = 22;

        this.mapButton = addDrawableChild(ButtonWidget.builder(mapButtonText(), b -> chooseFile())
                .dimensions(x, y, w, 20).build());
        y += gap;

        addDrawableChild(CyclingButtonWidget.<Boolean>builder(
                        v -> Text.literal(v ? "Blended (eroded mountains, biome lowlands)" : "Legacy"))
                .values(true, false).initially(blend)
                .build(x, y, w, 20, Text.literal("Terrain"), (btn, v) -> blend = v));
        y += gap;

        addDrawableChild(CyclingButtonWidget.<Double>builder(v -> Text.literal(trim(v) + " blocks/px"))
                .values(SCALES).initially(box(SCALES, scale))
                .build(x, y, w, 20, Text.literal("Map scale"), (btn, v) -> scale = v));
        y += gap;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(detail)
                .build(x, y, w, 20, Text.literal("Surface detail noise"), (btn, v) -> detail = v));
        y += gap;

        addDrawableChild(CyclingButtonWidget.<Double>builder(v -> Text.literal(trim(v)))
                .values(AMPLITUDES).initially(box(AMPLITUDES, amplitude))
                .build(x, y, w, 20, Text.literal("Detail amount (blocks)"), (btn, v) -> amplitude = v));
        y += gap;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(richBiomes)
                .build(x, y, w, 20, Text.literal("Biome variants"), (btn, v) -> richBiomes = v));
        y += gap;

        // Content profile: which biome mapping / block palettes / surface rules
        // this world generates with. Profiles needing a missing mod are hidden.
        addDrawableChild(CyclingButtonWidget.<com.example.profile.FmgContentProfile>builder(
                        pr -> Text.literal(pr.displayName))
                .values(profiles).initially(profile)
                .build(x, y, w, 20, Text.literal("Content"), (btn, v) -> profile = v));

        this.doneButton = addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> applyAndClose())
                .dimensions(cx - 155, this.height - 28, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
                .dimensions(cx + 5, this.height - 28, 150, 20).build());
        this.doneButton.active = jsonPath != null;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);
        if (jsonPath == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Choose an FMG JSON export to continue").formatted(Formatting.RED),
                    this.width / 2, this.height - 44, 0xFFFFFF);
        } else if (blend) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Build limit: Y " + (maxY() - 1) + "  (mountain height: terrain.peak in heightmap.json)")
                            .formatted(Formatting.GRAY),
                    this.width / 2, this.height - 44, 0xFFFFFF);
        }
    }

    private Text mapButtonText() {
        String name = jsonPath == null ? "(click to choose…)" : Path.of(jsonPath).getFileName().toString();
        return Text.literal("Map file: " + name);
    }

    private void chooseFile() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json"));
            filters.flip();
            String start = jsonPath != null ? jsonPath : "";
            String picked = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select FMG export", start, filters, "FMG JSON export (*.json)", false);
            if (picked != null && !picked.isBlank()) {
                jsonPath = picked;
                mapButton.setMessage(mapButtonText());
                doneButton.active = true;
            }
        } catch (Throwable t) {
            FantasyMapGenerator.LOGGER.warn("Native file dialog failed", t);
        }
    }

    private void applyAndClose() {
        if (jsonPath == null) {
            return;
        }
        sJson = jsonPath;
        sScale = scale;
        sDetail = detail;
        sAmplitude = amplitude;
        sRichBiomes = richBiomes;
        sProfile = profile.id;
        sBlend = blend;

        int maxY = maxY();
        MapInfo mapInfo = new MapInfo(jsonPath, MIN_Y, maxY, SEA_LEVEL, scale, detail, amplitude,
                richBiomes, profile.id, blend ? MapInfo.TERRAIN_BLEND : MapInfo.TERRAIN_LEGACY);
        RegistryKey<net.minecraft.world.gen.chunk.ChunkGeneratorSettings> settingsKey =
                RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, settingsIdentifier());
        parent.getWorldCreator().applyModifier((registries, dimensions) -> {
            var biomes = registries.getWrapperOrThrow(RegistryKeys.BIOME);
            var settings = registries.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS).entryOf(settingsKey);
            var generator = new ImageMapChunkGenerator(new ColorMapBiomeSource(biomes, mapInfo), settings, mapInfo);
            if (!mapInfo.blendTerrain()) {
                return dimensions.with(registries, generator);
            }
            var dimensionType = registries.get(RegistryKeys.DIMENSION_TYPE)
                    .entryOf(FmgHeightTiers.dimensionType(maxY));
            return new DimensionOptionsRegistryHolder(DimensionOptionsRegistryHolder.createRegistry(
                    dimensions.dimensions(), dimensionType, generator));
        });
        FmgCreateState.markApplied(parent.getWorldCreator(), mapInfo);
        close();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    // ---- helpers ----

    /** Build top for the current choice: a height tier for blended terrain, the old 400 otherwise. */
    private int maxY() {
        return blend ? FmgHeightTiers.topFor(SEA_LEVEL, HeightmapPostConfig.get().terrain) : LEGACY_MAX_Y;
    }

    private com.example.profile.FmgContentProfile pickProfile(String id) {
        for (com.example.profile.FmgContentProfile p : profiles) {
            if (p.id.equals(id)) return p;
        }
        return profiles.get(0);
    }

    /**
     * The profile's {@code noiseSettings} (and therefore its surface rules),
     * falling back to the bundled one if the profile names something missing.
     */
    private Identifier settingsIdentifier() {
        Identifier id = Identifier.tryParse(profile.noiseSettings);
        if (id == null) {
            FantasyMapGenerator.LOGGER.warn("Profile '{}' has an unparseable noiseSettings '{}'; using the default",
                    profile.id, profile.noiseSettings);
            return Identifier.of(FantasyMapGenerator.MOD_ID, "fmg_overworld_base");
        }
        return id;
    }

    private static Double box(List<Double> options, double value) {
        return options.get(indexOfNearest(options, value));
    }

    private static double nearest(List<Double> options, double value) {
        return options.get(indexOfNearest(options, value));
    }

    private static int indexOfNearest(List<Double> options, double value) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < options.size(); i++) {
            double d = Math.abs(options.get(i) - value);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }
}
