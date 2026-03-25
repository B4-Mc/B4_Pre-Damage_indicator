package com.b4.mod;

import com.b4.predamage.client.PreDamageHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class ClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println(">>> CLIENT INIT IS RUNNING! <<<");

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            PreDamageHud.processHand(client, drawContext, client.player.getMainHandStack(), true);
            PreDamageHud.processHand(client, drawContext, client.player.getOffHandStack(), false);
        });
    }
}
