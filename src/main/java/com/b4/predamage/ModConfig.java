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
    private static final boolean DEFAULT_SMOOTHING_ENABLED = true;
    private static final Color DEFAULT_LETHAL_DAMAGE_COLOR = new Color(0xFFFF0000, true);
    private static final Color DEFAULT_TOTEM_DAMAGE_COLOR = new Color(0xFFF2FF00, true);
    private static final Color DEFAULT_DAMAGE_POTION_COLOR = new Color(0xFF990000, true);
    private static final Color DEFAULT_HEALING_COLOR = new Color(0xFF00FF00, true);
    private static final int DEFAULT_DEBUG_X = 8;
    private static final int DEFAULT_DEBUG_Y = 8;
    private static final Color DEFAULT_DEBUG_NEUTRAL_COLOR = new Color(0xFFE6E6E6, true);
    private static final Color DEFAULT_DEBUG_INPUT_OUTPUT_COLOR = new Color(0xFF8FFF48, true);
    private static final Color DEFAULT_DEBUG_FINAL_OUTPUT_COLOR = new Color(0xFFFFA640, true);
    private static final Color DEFAULT_DEBUG_WARNING_COLOR = new Color(0xFFFFC04A, true);

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
    public static boolean smoothingEnabled = DEFAULT_SMOOTHING_ENABLED;
    public static Color lethalDamageColor = DEFAULT_LETHAL_DAMAGE_COLOR;
    public static Color totemDamageColor = DEFAULT_TOTEM_DAMAGE_COLOR;
    public static Color damagePotionColor = DEFAULT_DAMAGE_POTION_COLOR;
    public static Color healingColor = DEFAULT_HEALING_COLOR;
    public static int debugX = DEFAULT_DEBUG_X;
    public static int debugY = DEFAULT_DEBUG_Y;
    public static boolean debugShowItem = true;
    public static boolean debugShowTarget = true;
    public static boolean debugShowHealth = true;
    public static boolean debugShowInput = true;
    public static boolean debugShowCrit = true;
    public static boolean debugShowEnchantments = true;
    public static boolean debugShowMagic = true;
    public static boolean debugShowSpear = true;
    public static boolean debugShowMace = true;
    public static boolean debugShowState = true;
    public static boolean debugShowReductions = true;
    public static boolean debugShowFinal = true;
    public static boolean debugShowWarnings = true;
    public static Color debugNeutralColor = DEFAULT_DEBUG_NEUTRAL_COLOR;
    public static Color debugInputOutputColor = DEFAULT_DEBUG_INPUT_OUTPUT_COLOR;
    public static Color debugFinalOutputColor = DEFAULT_DEBUG_FINAL_OUTPUT_COLOR;
    public static Color debugWarningColor = DEFAULT_DEBUG_WARNING_COLOR;

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
            smoothingEnabled = data.smoothingEnabled;
            lethalDamageColor = new Color(data.lethalDamageColorRgb, true);
            totemDamageColor = new Color(data.totemDamageColorRgb, true);
            damagePotionColor = new Color(data.damagePotionColorRgb, true);
            healingColor = new Color(data.healingColorRgb, true);
            debugX = data.debugX;
            debugY = data.debugY;
            debugShowItem = data.debugShowItem;
            debugShowTarget = data.debugShowTarget;
            debugShowHealth = data.debugShowHealth;
            debugShowInput = data.debugShowInput;
            debugShowCrit = data.debugShowCrit;
            debugShowEnchantments = data.debugShowEnchantments;
            debugShowMagic = data.debugShowMagic;
            debugShowSpear = data.debugShowSpear;
            debugShowMace = data.debugShowMace;
            debugShowState = data.debugShowState;
            debugShowReductions = data.debugShowReductions;
            debugShowFinal = data.debugShowFinal;
            debugShowWarnings = data.debugShowWarnings;
            debugNeutralColor = new Color(data.debugNeutralColorRgb, true);
            debugInputOutputColor = new Color(data.debugInputOutputColorRgb, true);
            debugFinalOutputColor = new Color(data.debugFinalOutputColorRgb, true);
            debugWarningColor = new Color(data.debugWarningColorRgb, true);
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
        data.smoothingEnabled = smoothingEnabled;
        data.lethalDamageColorRgb = colorRgb(lethalDamageColor, DEFAULT_LETHAL_DAMAGE_COLOR);
        data.totemDamageColorRgb = colorRgb(totemDamageColor, DEFAULT_TOTEM_DAMAGE_COLOR);
        data.damagePotionColorRgb = colorRgb(damagePotionColor, DEFAULT_DAMAGE_POTION_COLOR);
        data.healingColorRgb = colorRgb(healingColor, DEFAULT_HEALING_COLOR);
        data.debugX = debugX;
        data.debugY = debugY;
        data.debugShowItem = debugShowItem;
        data.debugShowTarget = debugShowTarget;
        data.debugShowHealth = debugShowHealth;
        data.debugShowInput = debugShowInput;
        data.debugShowCrit = debugShowCrit;
        data.debugShowEnchantments = debugShowEnchantments;
        data.debugShowMagic = debugShowMagic;
        data.debugShowSpear = debugShowSpear;
        data.debugShowMace = debugShowMace;
        data.debugShowState = debugShowState;
        data.debugShowReductions = debugShowReductions;
        data.debugShowFinal = debugShowFinal;
        data.debugShowWarnings = debugShowWarnings;
        data.debugNeutralColorRgb = colorRgb(debugNeutralColor, DEFAULT_DEBUG_NEUTRAL_COLOR);
        data.debugInputOutputColorRgb = colorRgb(debugInputOutputColor, DEFAULT_DEBUG_INPUT_OUTPUT_COLOR);
        data.debugFinalOutputColorRgb = colorRgb(debugFinalOutputColor, DEFAULT_DEBUG_FINAL_OUTPUT_COLOR);
        data.debugWarningColorRgb = colorRgb(debugWarningColor, DEFAULT_DEBUG_WARNING_COLOR);

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

    private static int colorRgb(Color value, Color fallback) {
        return value != null ? value.getRGB() : fallback.getRGB();
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
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Lethal Damage Color"))
                                .binding(DEFAULT_LETHAL_DAMAGE_COLOR, () -> lethalDamageColor, val -> lethalDamageColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Totem Activation Color"))
                                .binding(DEFAULT_TOTEM_DAMAGE_COLOR, () -> totemDamageColor, val -> totemDamageColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Damage Potion Color"))
                                .binding(DEFAULT_DAMAGE_POTION_COLOR, () -> damagePotionColor, val -> damagePotionColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Healing Color"))
                                .binding(DEFAULT_HEALING_COLOR, () -> healingColor, val -> healingColor = val)
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
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Smoothing"))
                                .binding(DEFAULT_SMOOTHING_ENABLED, () -> smoothingEnabled, val -> smoothingEnabled = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Debug Overlay"))
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Debug X Position"))
                                .binding(DEFAULT_DEBUG_X, () -> debugX, val -> debugX = val)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 512).step(1))
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Debug Y Position"))
                                .binding(DEFAULT_DEBUG_Y, () -> debugY, val -> debugY = val)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 512).step(1))
                                .build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Item")).binding(true, () -> debugShowItem, val -> debugShowItem = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Target")).binding(true, () -> debugShowTarget, val -> debugShowTarget = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Health")).binding(true, () -> debugShowHealth, val -> debugShowHealth = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Input")).binding(true, () -> debugShowInput, val -> debugShowInput = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Crit")).binding(true, () -> debugShowCrit, val -> debugShowCrit = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Enchantments")).binding(true, () -> debugShowEnchantments, val -> debugShowEnchantments = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Magic")).binding(true, () -> debugShowMagic, val -> debugShowMagic = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Spear")).binding(true, () -> debugShowSpear, val -> debugShowSpear = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Mace")).binding(true, () -> debugShowMace, val -> debugShowMace = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show State")).binding(true, () -> debugShowState, val -> debugShowState = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Reductions")).binding(true, () -> debugShowReductions, val -> debugShowReductions = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Final")).binding(true, () -> debugShowFinal, val -> debugShowFinal = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Boolean>createBuilder().name(Text.literal("Show Warnings")).binding(true, () -> debugShowWarnings, val -> debugShowWarnings = val).controller(TickBoxControllerBuilder::create).build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Neutral Info Color"))
                                .binding(DEFAULT_DEBUG_NEUTRAL_COLOR, () -> debugNeutralColor, val -> debugNeutralColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Input/Output Color"))
                                .binding(DEFAULT_DEBUG_INPUT_OUTPUT_COLOR, () -> debugInputOutputColor, val -> debugInputOutputColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Final Output Color"))
                                .binding(DEFAULT_DEBUG_FINAL_OUTPUT_COLOR, () -> debugFinalOutputColor, val -> debugFinalOutputColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Warning Color"))
                                .binding(DEFAULT_DEBUG_WARNING_COLOR, () -> debugWarningColor, val -> debugWarningColor = val)
                                .controller(ColorControllerBuilder::create)
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
        boolean smoothingEnabled = DEFAULT_SMOOTHING_ENABLED;
        int lethalDamageColorRgb = DEFAULT_LETHAL_DAMAGE_COLOR.getRGB();
        int totemDamageColorRgb = DEFAULT_TOTEM_DAMAGE_COLOR.getRGB();
        int damagePotionColorRgb = DEFAULT_DAMAGE_POTION_COLOR.getRGB();
        int healingColorRgb = DEFAULT_HEALING_COLOR.getRGB();
        int debugX = DEFAULT_DEBUG_X;
        int debugY = DEFAULT_DEBUG_Y;
        boolean debugShowItem = true;
        boolean debugShowTarget = true;
        boolean debugShowHealth = true;
        boolean debugShowInput = true;
        boolean debugShowCrit = true;
        boolean debugShowEnchantments = true;
        boolean debugShowMagic = true;
        boolean debugShowSpear = true;
        boolean debugShowMace = true;
        boolean debugShowState = true;
        boolean debugShowReductions = true;
        boolean debugShowFinal = true;
        boolean debugShowWarnings = true;
        int debugNeutralColorRgb = DEFAULT_DEBUG_NEUTRAL_COLOR.getRGB();
        int debugInputOutputColorRgb = DEFAULT_DEBUG_INPUT_OUTPUT_COLOR.getRGB();
        int debugFinalOutputColorRgb = DEFAULT_DEBUG_FINAL_OUTPUT_COLOR.getRGB();
        int debugWarningColorRgb = DEFAULT_DEBUG_WARNING_COLOR.getRGB();
    }
}
