package com.b4.mod;

import com.b4.predamage.client.PreDamageHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class ClientInit implements ClientModInitializer {
    public static final PreDamageHud HUD_INSTANCE = new PreDamageHud();

    @Override
    public void onInitializeClient() {
        System.out.println(">>> CLIENT INIT IS RUNNING! <<<");

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null) return;

            TextRenderer renderer = client.textRenderer;

            // 3. Run your HUD logic
            if (HUD_INSTANCE != null) {
                HUD_INSTANCE.onHudRender(drawContext, tickCounter);
            }
        });
    }
}