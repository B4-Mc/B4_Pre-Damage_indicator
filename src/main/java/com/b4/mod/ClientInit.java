package com.b4.mod;

import com.b4.predamage.client.PreDamageHud;
import com.b4.predamage.client.DamageLogger;
import com.b4.predamage.client.DamageReconciliationTracker;
import com.b4.predamage.client.HealLogger;
import com.b4.predamage.client.ResistanceStateManager;
import com.b4.predamage.client.SplashBindingTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;

public class ClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println(">>> CLIENT INIT IS RUNNING! <<<");

        // Initialize logger sessions safely at client startup
        DamageLogger.init();
        HealLogger.init();

        // Heal detection runs every client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HealLogger.tick(client);
            DamageReconciliationTracker.tick(client);
            SplashBindingTracker.tick(client);
            ResistanceStateManager.tick(client);
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            PreDamageHud.onEntityAttack(player, hand, entity);
            return ActionResult.PASS;
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            PreDamageHud.processHand(client, drawContext, client.player.getMainHandStack(), true);
            PreDamageHud.processHand(client, drawContext, client.player.getOffHandStack(), false);
        });
    }
}
