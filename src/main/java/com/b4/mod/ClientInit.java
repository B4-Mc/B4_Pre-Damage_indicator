package com.b4.mod;

import com.b4.predamage.client.PreDamageHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class ClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println(">>> CLIENT INIT IS RUNNING! <<<");

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("b4_pre-damage_indicator", "indicator"), (drawContext, tickDelta) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;

            PreDamageHud.processHand(client, drawContext, client.player.getMainHandItem(), true);
            PreDamageHud.processHand(client, drawContext, client.player.getOffhandItem(), false);
        });
    }
}
