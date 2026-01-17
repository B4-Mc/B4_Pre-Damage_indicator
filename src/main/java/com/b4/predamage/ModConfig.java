package com.b4.predamage;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.awt.Color;

public class ModConfig {
    public static boolean modEnabled = true;
    public static Color indicatorColor = Color.WHITE;
    public static int opacity = 255;
    public static Screen createGui(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("B4 Pre-Damage Settings"))
                .save(() -> {
                    System.out.println("Settings saved!");
                })
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("General"))
                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Enable Indicator"))
                                .binding(true, () -> modEnabled, val -> modEnabled = val)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Color>createBuilder()
                                .name(Text.literal("Indicator Color"))
                                .binding(Color.WHITE, () -> indicatorColor, val -> indicatorColor = val)
                                .controller(ColorControllerBuilder::create)
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Indicator Opacity"))
                                .binding(255, () -> opacity, val -> opacity = val)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 255).step(5))
                                .build())
                        .build())
                .build()
                .generateScreen(parent);
    }
}