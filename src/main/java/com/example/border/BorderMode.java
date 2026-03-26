package com.example.border;

public enum BorderMode {
    OFF(0),
    PROVINCE(1),
    STATE(2);

    public final int id;

    BorderMode(int id) {
        this.id = id;
    }

    public static BorderMode fromId(int id) {
        for (BorderMode mode : values()) {
            if (mode.id == id) return mode;
        }
        return OFF;
    }
}
