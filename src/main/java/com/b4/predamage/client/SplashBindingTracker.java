package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks splash potion applications in a structured, non-polling way.
 */
public class SplashBindingTracker {

    private static final Map<UUID, BoundResistance> SPLASH_RESISTANCE_LEVEL = new HashMap<>();
    private static final long DEFAULT_TURTLE_SPLASH_TTL_MS = 10_000L;

    public static void tick(MinecraftClient client) {
        if (client.world == null) return;

        pruneExpired();

        for (var entity : client.world.getEntities()) {
            if (entity instanceof PotionEntity potion) {

                // Bind whenever splash/lingering potion exists.
                // Do NOT rely on velocity (impact timing is unreliable client-side).

                ItemStack stack = potion.getStack();
                if (!stack.contains(DataComponentTypes.POTION_CONTENTS)) continue;

                var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
                BoundResistance resistance = getTurtleResistance(contents.getEffects());
                if (resistance.level <= 0) continue;

                var nearby = client.world.getEntitiesByClass(
                        LivingEntity.class,
                        potion.getBoundingBox().expand(4.0),
                        e -> true
                );

                long now = System.currentTimeMillis();

                for (LivingEntity living : nearby) {
                    BoundResistance existing = SPLASH_RESISTANCE_LEVEL.get(living.getUuid());
                    if (existing == null || existing.isExpired(now)) {
                        SPLASH_RESISTANCE_LEVEL.put(living.getUuid(), resistance);
                    }
                }
            }
        }
    }

    public static int getSplashResistance(LivingEntity target) {
        BoundResistance bound = SPLASH_RESISTANCE_LEVEL.get(target.getUuid());
        if (bound == null) {
            return 0;
        }
        if (bound.isExpired(System.currentTimeMillis())) {
            SPLASH_RESISTANCE_LEVEL.remove(target.getUuid());
            return 0;
        }
        return bound.level;
    }

    public static long getSplashRemainingMs(LivingEntity target) {
        BoundResistance bound = SPLASH_RESISTANCE_LEVEL.get(target.getUuid());
        if (bound == null) {
            return DEFAULT_TURTLE_SPLASH_TTL_MS;
        }
        return Math.max(1L, bound.expiresAt - System.currentTimeMillis());
    }

    public static void clear(LivingEntity target) {
        SPLASH_RESISTANCE_LEVEL.remove(target.getUuid());
    }

    private static BoundResistance getTurtleResistance(Iterable<StatusEffectInstance> effects) {
        int level = 0;
        long durationMs = DEFAULT_TURTLE_SPLASH_TTL_MS;

        for (StatusEffectInstance effect : effects) {
            if (effect.getEffectType().equals(StatusEffects.RESISTANCE)) {
                level = Math.max(level, effect.getAmplifier() + 1);
                durationMs = Math.max(durationMs, durationMs(effect));
            } else if (effect.getEffectType().equals(StatusEffects.SLOWNESS)) {
                int slownessLevel = effect.getAmplifier() + 1;
                if (slownessLevel >= 6) {
                    level = Math.max(level, 4);
                } else if (slownessLevel >= 4) {
                    level = Math.max(level, 3);
                } else if (slownessLevel > 0) {
                    level = Math.max(level, 4);
                }
                durationMs = Math.max(durationMs, durationMs(effect));
            }
        }

        if (level <= 0) {
            return BoundResistance.NONE;
        }
        return new BoundResistance(level, System.currentTimeMillis() + durationMs);
    }

    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        Iterator<BoundResistance> iterator = SPLASH_RESISTANCE_LEVEL.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isExpired(now)) {
                iterator.remove();
            }
        }
    }

    private static long durationMs(StatusEffectInstance effect) {
        int ticks = effect.getDuration();
        return ticks > 0 ? ticks * 50L : DEFAULT_TURTLE_SPLASH_TTL_MS;
    }

    private record BoundResistance(int level, long expiresAt) {
        private static final BoundResistance NONE = new BoundResistance(0, 0L);

        boolean isExpired(long now) {
            return expiresAt > 0L && now >= expiresAt;
        }
    }
}
