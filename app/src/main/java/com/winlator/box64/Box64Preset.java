package com.winlator.box64;

import androidx.annotation.NonNull;

public class Box64Preset {
    public static final String STABILITY = "STABILITY";
    public static final String CONSERVATIVE = "CONSERVATIVE";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String CUSTOM = "CUSTOM";
    // Compatibility (兼容模式) is the safer default for a general purpose front end: the
    // performance preset turns on fastnan/fastround, drops x87 to 64 bit precision and raises
    // bigblock to 3, which breaks a fair number of older games with wrong math or hard crashes.
    // Anyone who wants the extra speed can still pick it per container.
    public static final String DEFAULT = CONSERVATIVE;
    public final String id;
    public final String name;

    public Box64Preset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isCustom() {
        return id.startsWith(CUSTOM);
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }
}