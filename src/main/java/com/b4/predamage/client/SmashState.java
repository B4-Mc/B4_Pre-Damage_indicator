package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;

/**
 * SmashState mirrors vanilla mace smash lifecycle without mutating player state.
 * It tracks fall phase, consumption, and reset rules deterministically.
 */
public final class SmashState {

    private boolean falling = false;
    private float trackedFallDistance = 0.0f;
    private float consumedFallDistance = 0.0f;
    private boolean justConsumed = false;
    private boolean consumedCurrentSwing = false;

    public void tick(MinecraftClient client) {
        tick(client == null ? null : client.player);
    }

    public void tick(PlayerEntity player) {
        if (player == null) {
            reset();
            return;
        }

        justConsumed = false;
        if (!player.handSwinging) {
            consumedCurrentSwing = false;
        }

        if (hasFallEnded(player)) {
            reset();
            return;
        }

        float realFall = currentFallDistance(player);
        if (realFall < consumedFallDistance) {
            consumedFallDistance = realFall;
        }

        float activeFallDistance = Math.max(0.0f, realFall - consumedFallDistance);
        if (activeFallDistance > trackedFallDistance) {
            trackedFallDistance = activeFallDistance;
        }

        falling = trackedFallDistance > 1.5f;
    }

    public float getTrackedFallDistance() {
        return trackedFallDistance;
    }

    public boolean canApplySmash() {
        return falling;
    }

    public boolean consumeIfSwinging(MinecraftClient client) {
        PlayerEntity player = client == null ? null : client.player;
        return player != null && player.handSwinging && consumeSmash(player);
    }

    public boolean consumeSmash(PlayerEntity player) {
        if (player == null || consumedCurrentSwing || !falling) return false;

        consumedFallDistance = Math.max(consumedFallDistance, currentFallDistance(player));
        trackedFallDistance = 0.0f;
        falling = false;
        justConsumed = true;
        consumedCurrentSwing = true;
        return true;
    }

    public void reset() {
        falling = false;
        trackedFallDistance = 0.0f;
        consumedFallDistance = 0.0f;
        justConsumed = false;
        consumedCurrentSwing = false;
    }

    public boolean wasJustConsumed() {
        return justConsumed;
    }

    private static boolean hasFallEnded(PlayerEntity player) {
        return player.isOnGround()
                || player.isTouchingWater()
                || player.isClimbing()
                || player.hasVehicle()
                || player.isGliding()
                || player.hasStatusEffect(StatusEffects.SLOW_FALLING);
    }

    private static float currentFallDistance(PlayerEntity player) {
        return Math.max(0.0f, (float) player.fallDistance);
    }
}
