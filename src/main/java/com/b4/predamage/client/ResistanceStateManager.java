package com.b4.predamage.client;

import com.b4.predamage.mixin.LivingEntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central resistance inference system.
 * All resistance logic will be routed through here.
 */
public class ResistanceStateManager {

    private static final Map<UUID, ResistanceState> CACHE = new HashMap<>();
    private static final long SHORT_INFERRED_TTL_MS = 4_000L;
    private static final long TURTLE_INFERRED_TTL_MS = 10_000L;
    private static final long GOLDEN_APPLE_TTL_MS = 300_000L;
    private static final long LOGGER_CORRECTION_TTL_MS = 15_000L;
    private static final float HIGH_CONFIDENCE = 0.70f;

    public static int resolveResistanceLevel(MinecraftClient client, LivingEntity target) {
        UUID id = target.getUuid();
        ResistanceState state = CACHE.computeIfAbsent(id, ResistanceState::new);
        long now = System.currentTimeMillis();

        // 1. Direct sync (valid for all entities)
        StatusEffectInstance direct = target.getStatusEffect(StatusEffects.RESISTANCE);
        if (direct != null) {
            int directLevel = direct.getAmplifier() + 1;
            state.update(directLevel, ResistanceState.SourceType.DIRECT, 1.0f, durationMs(direct, SHORT_INFERRED_TTL_MS));
            return directLevel;
        }
        if (state.getSource() == ResistanceState.SourceType.DIRECT || state.isExpired(now)) {
            state.clear();
        }

        // 2. Splash binding
        int splashLevel = SplashBindingTracker.getSplashResistance(target);
        if (splashLevel > 0) {
            state.update(splashLevel, ResistanceState.SourceType.SPLASH, 0.85f, SplashBindingTracker.getSplashRemainingMs(target));
            return splashLevel;
        }

        // 3. Cached high-confidence state
        if (state.isUsable(now, HIGH_CONFIDENCE)) {
            return state.getLevel();
        }

        // 4. Particle and status fallback inference
        Candidate inferred = inferFromVisibleState(client, target);
        if (inferred.level > 0) {
            state.update(inferred.level, inferred.source, inferred.confidence, inferred.durationMs);
            return inferred.level;
        }

        return 0;
    }

    public static void correctFromLogger(LivingEntity target, int correctedLevel) {
        ResistanceState state = CACHE.computeIfAbsent(target.getUuid(), ResistanceState::new);
        if (correctedLevel <= 0) {
            state.clear();
            return;
        }
        state.update(correctedLevel, ResistanceState.SourceType.LOGGER_CORRECTED, 0.9f, LOGGER_CORRECTION_TTL_MS);
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null) {
            CACHE.clear();
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<ResistanceState> iterator = CACHE.values().iterator();
        while (iterator.hasNext()) {
            ResistanceState state = iterator.next();
            if (state.isExpired(now)) {
                iterator.remove();
            }
        }
    }

    public static void clear(LivingEntity target) {
        CACHE.remove(target.getUuid());
    }

    private static Candidate inferFromVisibleState(MinecraftClient client, LivingEntity target) {
        boolean hasResistanceParticle = hasStatusEffectParticle(target, StatusEffects.RESISTANCE);
        int slownessLevel = getStatusEffectLevel(target, StatusEffects.SLOWNESS);

        if (hasResistanceParticle && slownessLevel >= 6) {
            return new Candidate(4, ResistanceState.SourceType.INFERRED, 0.75f, TURTLE_INFERRED_TTL_MS);
        }
        if (hasResistanceParticle && slownessLevel >= 4) {
            return new Candidate(3, ResistanceState.SourceType.INFERRED, 0.75f, TURTLE_INFERRED_TTL_MS);
        }

        Candidate sharedBeacon = inferSharedBeacon(client, target, hasResistanceParticle);
        if (sharedBeacon.level > 0) {
            return sharedBeacon;
        }

        if (hasResistanceParticle && target instanceof PlayerEntity playerTarget) {
            double baseSpeed = playerTarget.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED);
            double currentSpeed = playerTarget.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
            if (baseSpeed > 0.0) {
                double speedRatio = currentSpeed / baseSpeed;
                if (speedRatio < 0.45) {
                    return new Candidate(4, ResistanceState.SourceType.INFERRED, 0.65f, TURTLE_INFERRED_TTL_MS);
                }
                if (speedRatio < 0.70) {
                    return new Candidate(3, ResistanceState.SourceType.INFERRED, 0.60f, TURTLE_INFERRED_TTL_MS);
                }
            }
        }

        if (hasResistanceParticle && slownessLevel > 0) {
            return new Candidate(4, ResistanceState.SourceType.INFERRED, 0.60f, TURTLE_INFERRED_TTL_MS);
        }

        // Enchanted Golden Apple gives Resistance I without slowness.
        // Only infer if resistance particle exists AND no slowness AND no splash binding active.
        if (hasResistanceParticle && slownessLevel == 0
                && SplashBindingTracker.getSplashResistance(target) == 0) {
            return new Candidate(1, ResistanceState.SourceType.GOLDEN_APPLE, 0.55f, GOLDEN_APPLE_TTL_MS);
        }

        return Candidate.NONE;
    }

    private static Candidate inferSharedBeacon(MinecraftClient client, LivingEntity target, boolean targetHasResistanceParticle) {
        // Beacon logic must ONLY apply to players.
        if (!(target instanceof PlayerEntity)) {
            return Candidate.NONE;
        }
        if (client == null || client.player == null || client.player == target) {
            return Candidate.NONE;
        }

        int playerResistance = getStatusEffectLevel(client.player, StatusEffects.RESISTANCE);
        if (playerResistance <= 0 || !hasStatusEffectParticle(client.player, StatusEffects.RESISTANCE)) {
            return Candidate.NONE;
        }

        // Beacon only provides Resistance I or II.
        // Prevent overriding higher-level effects like Turtle Master (III/IV).
        if (playerResistance > 2) {
            return Candidate.NONE;
        }

        if (client.player.distanceTo(target) > 50.0f) {
            return Candidate.NONE;
        }

        return new Candidate(playerResistance, ResistanceState.SourceType.BEACON, 0.60f, SHORT_INFERRED_TTL_MS);
    }

    private static int getStatusEffectLevel(LivingEntity target, RegistryEntry<StatusEffect> effect) {
        StatusEffectInstance direct = target.getStatusEffect(effect);
        if (direct != null) {
            return direct.getAmplifier() + 1;
        }
        for (StatusEffectInstance instance : target.getStatusEffects()) {
            if (instance.getEffectType().matches(effect)) {
                return instance.getAmplifier() + 1;
            }
        }
        return 0;
    }

    private static boolean hasStatusEffectParticle(LivingEntity target, RegistryEntry<StatusEffect> effect) {
        try {
            List<ParticleEffect> particles = target.getDataTracker().get(LivingEntityAccessor.predamage$getPotionSwirls());
            int expectedRgb = effect.value().getColor() & 0x00FFFFFF;
            for (ParticleEffect particle : particles) {
                if (particle instanceof TintedParticleEffect tinted && colorDistanceSquared(toRgb(tinted), expectedRgb) <= 9) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Direct synced effects remain the authoritative path when the tracked particle list is unavailable.
        }
        return false;
    }

    private static int toRgb(TintedParticleEffect particle) {
        int red = MathHelper.clamp(Math.round(particle.getRed() * 255.0f), 0, 255);
        int green = MathHelper.clamp(Math.round(particle.getGreen() * 255.0f), 0, 255);
        int blue = MathHelper.clamp(Math.round(particle.getBlue() * 255.0f), 0, 255);
        return (red << 16) | (green << 8) | blue;
    }

    private static int colorDistanceSquared(int leftRgb, int rightRgb) {
        int red = ((leftRgb >> 16) & 0xFF) - ((rightRgb >> 16) & 0xFF);
        int green = ((leftRgb >> 8) & 0xFF) - ((rightRgb >> 8) & 0xFF);
        int blue = (leftRgb & 0xFF) - (rightRgb & 0xFF);
        return red * red + green * green + blue * blue;
    }

    private static long durationMs(StatusEffectInstance instance, long fallbackMs) {
        int durationTicks = instance.getDuration();
        if (durationTicks <= 0) {
            return fallbackMs;
        }
        return Math.max(fallbackMs, durationTicks * 50L);
    }

    private record Candidate(int level, ResistanceState.SourceType source, float confidence, long durationMs) {
        private static final Candidate NONE = new Candidate(0, ResistanceState.SourceType.NONE, 0.0f, 0L);
    }
}
