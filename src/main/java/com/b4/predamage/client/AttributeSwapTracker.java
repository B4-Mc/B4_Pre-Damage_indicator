package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Attribute Swap Detection Layer
 *
 * Detects sudden changes in:
 * - ATTACK_DAMAGE
 * - ATTACK_SPEED
 *
 * Used for PvP exploit detection (armor/weapon swap abuse).
 */
public class AttributeSwapTracker {

    private static class Snapshot {
        final double attackDamage;
        final double attackSpeed;

        Snapshot(double attackDamage, double attackSpeed) {
            this.attackDamage = attackDamage;
            this.attackSpeed = attackSpeed;
        }
    }

    private static final Map<Integer, Snapshot> LAST_SNAPSHOTS = new HashMap<>();

    /**
     * Returns 1 if swap detected during this tick, otherwise 0.
     */
    public static int detectSwap(MinecraftClient client) {
        if (client == null || client.player == null) return 0;

        PlayerEntity player = client.player;
        int id = player.getId();

        double damage = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        double speed = player.getAttributeValue(EntityAttributes.ATTACK_SPEED);

        if (LAST_SNAPSHOTS.containsKey(id)) {
            Snapshot prev = LAST_SNAPSHOTS.get(id);

            boolean damageChanged = Math.abs(prev.attackDamage - damage) > 0.001;
            boolean speedChanged = Math.abs(prev.attackSpeed - speed) > 0.001;

            LAST_SNAPSHOTS.put(id, new Snapshot(damage, speed));

            if (damageChanged || speedChanged) {
                return 1;
            }

            return 0;
        }

        LAST_SNAPSHOTS.put(id, new Snapshot(damage, speed));
        return 0;
    }
}