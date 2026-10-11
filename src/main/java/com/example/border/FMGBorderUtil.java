package com.example.border;

import net.minecraft.text.TextColor;

public final class FMGBorderUtil {
    private FMGBorderUtil() {
    }

    public static int parseRgbOrFallback(String raw, int fallbackRgb) {
        if (raw == null || raw.isBlank()) {
            return fallbackRgb;
        }
        TextColor parsed = TextColor.parse(raw).result().orElse(null);
        return parsed != null ? parsed.getRgb() : fallbackRgb;
    }
}
