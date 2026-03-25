package com.b4.predamage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final boolean DEFAULT_MOD_ENABLED = true;
    private static final Color DEFAULT_INDICATOR_COLOR = Color.WHITE;
    private static final int DEFAULT_OPACITY = 255;
    private static final boolean DEFAULT_DEBUG_OVERLAY_ENABLED = false;
    private static final boolean DEFAULT_SHOW_INACCURACY_WARNINGS = true;
    private static final boolean DEFAULT_ENABLE_INTERACTION_HEALING = true;
    private static final boolean DEFAULT_ENABLE_EXTENDED_CROSSBOW_REACH = true;
    private static final boolean DEFAULT_ENABLE_VERTICAL_MACE_REACH = true;

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("b4-pre-damage-indicator.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean modEnabled = DEFAULT_MOD_ENABLED;
    public static Color indicatorColor = DEFAULT_INDICATOR_COLOR;
    public static int opacity = DEFAULT_OPACITY;
    public static boolean debugOverlayEnabled = DEFAULT_DEBUG_OVERLAY_ENABLED;
    public static boolean showInaccuracyWarnings = DEFAULT_SHOW_INACCURACY_WARNINGS;
    public static boolean enableInteractionHealing = DEFAULT_ENABLE_INTERACTION_HEALING;
    public static boolean enableExtendedCrossbowReach = DEFAULT_ENABLE_EXTENDED_CROSSBOW_REACH;
    public static boolean enableVerticalMaceReach = DEFAULT_ENABLE_VERTICAL_MACE_REACH;

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try {
            ConfigData data = GSON.fromJson(Files.readString(CONFIG_PATH), ConfigData.class);
            if (data == null) {
                save();
                return;
            }

            modEnabled = data.modEnabled;
            indicatorColor = new Color(data.indicatorColorRgb, true);
            opacity = clampOpacity(data.opacity);
            debugOverlayEnabled = data.debugOverlayEnabled;
            showInaccuracyWarnings = data.showInaccuracyWarnings;
            enableInteractionHealing = data.enableInteractionHealing;
            enableExtendedCrossbowReach = data.enableExtendedCrossbowReach;
            enableVerticalMaceReach = data.enableVerticalMaceReach;
        } catch (Exception e) {
            System.err.println("[B4 Pre-Damage] Failed to load config: " + e.getMessage());
        }
    }

    public static void save() {
        ConfigData data = new ConfigData();
        data.modEnabled = modEnabled;
        data.indicatorColorRgb = indicatorColor != null ? indicatorColor.getRGB() : DEFAULT_INDICATOR_COLOR.getRGB();
        data.opacity = clampOpacity(opacity);
        data.debugOverlayEnabled = debugOverlayEnabled;
        data.showInaccuracyWarnings = showInaccuracyWarnings;
        data.enableInteractionHealing = enableInteractionHealing;
        data.enableExtendedCrossbowReach = enableExtendedCrossbowReach;
        data.enableVerticalMaceReach = enableVerticalMaceReach;

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
        } catch (Exception e) {
            System.err.println("[B4 Pre-Damage] Failed to save config: " + e.getMessage());
        }
    }

    private static int clampOpacity(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static Screen createGui(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("B4 Pre-Damage Settings"))
                .save(ModConfig::save)
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("General"))
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Enable Indicator"))
                                .binding(DEFAULT_MOD_ENABLED, () -> modEnabled, val -> modEnabled = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Indicator Color"))
                                .binding(DEFAULT_INDICATOR_COLOR, () -> indicatorColor, val -> indicatorColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Indicator Opacity"))
                                .binding(DEFAULT_OPACITY, () -> opacity, val -> opacity = clampOpacity(val))
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 255).step(5))
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Enable Debug Overlay"))
                                .binding(DEFAULT_DEBUG_OVERLAY_ENABLED, () -> debugOverlayEnabled, val -> debugOverlayEnabled = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Show Accuracy Warnings"))
                                .binding(DEFAULT_SHOW_INACCURACY_WARNINGS, () -> showInaccuracyWarnings, val -> showInaccuracyWarnings = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Interaction Healing"))
                                .binding(DEFAULT_ENABLE_INTERACTION_HEALING, () -> enableInteractionHealing, val -> enableInteractionHealing = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Crossbow Long Reach"))
                                .binding(DEFAULT_ENABLE_EXTENDED_CROSSBOW_REACH, () -> enableExtendedCrossbowReach, val -> enableExtendedCrossbowReach = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Mace Vertical Reach"))
                                .binding(DEFAULT_ENABLE_VERTICAL_MACE_REACH, () -> enableVerticalMaceReach, val -> enableVerticalMaceReach = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .build())
                .build()
                .generateScreen(parent);
    }

    private static class ConfigData {
        boolean modEnabled = DEFAULT_MOD_ENABLED;
        int indicatorColorRgb = DEFAULT_INDICATOR_COLOR.getRGB();
        int opacity = DEFAULT_OPACITY;
        boolean debugOverlayEnabled = DEFAULT_DEBUG_OVERLAY_ENABLED;
        boolean showInaccuracyWarnings = DEFAULT_SHOW_INACCURACY_WARNINGS;
        boolean enableInteractionHealing = DEFAULT_ENABLE_INTERACTION_HEALING;
        boolean enableExtendedCrossbowReach = DEFAULT_ENABLE_EXTENDED_CROSSBOW_REACH;
        boolean enableVerticalMaceReach = DEFAULT_ENABLE_VERTICAL_MACE_REACH;
    }
}
