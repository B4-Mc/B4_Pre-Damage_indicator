package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Dev-Grade Reconciliation System
 *
 * - Detects real health delta
 * - Updates latest matching DamageLogEntry
 * - Appends reconciliation line
 */
public class DamageReconciliationTracker {

    private static final Map<Integer, Float> LAST_HEALTH = new HashMap<>();
    private static final long MAX_CORRECTION_AGE_SECONDS = 2L;
    private static final float MAX_HEALTH_BASELINE_DRIFT = 0.6f;
    private static final float MIN_CORRECTION_ERROR = 0.35f;

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null) return;

        for (var entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;

            int id = living.getId();
            float currentHealth = living.getHealth() + living.getAbsorptionAmount();

            if (LAST_HEALTH.containsKey(id)) {
                float previous = LAST_HEALTH.get(id);

                if (currentHealth < previous) {
                    float delta = previous - currentHealth;

                    DamageLogEntry last = DamageLogger.findLatestUnreconciledForTarget(id);
                    if (last != null) {
                        last.actualDamage = delta;
                        last.healthAfter = currentHealth;

                        DamageLogger.appendReconciliation(last);
                        maybeCorrectResistance(living, last, previous, delta);
                    }
                }
            }

            LAST_HEALTH.put(id, currentHealth);
        }
    }

    private static void maybeCorrectResistance(
            LivingEntity target,
            DamageLogEntry entry,
            float previousHealth,
            float actualDamage
    ) {
        if (!isTrustworthyCorrectionSnapshot(entry, previousHealth, actualDamage)) {
            return;
        }

        int correctedLevel = inferResistanceLevel(entry, actualDamage);

        // Always push correction if we have a better-fit level,
        // even if entry.resistance was 0 or inferred incorrectly.
        if (correctedLevel != entry.resistance) {
            ResistanceStateManager.correctFromLogger(target, correctedLevel);
        }
    }

    private static boolean isTrustworthyCorrectionSnapshot(
            DamageLogEntry entry,
            float previousHealth,
            float actualDamage
    ) {
        if (entry.predictedDamage <= 0.0f || actualDamage <= 0.0f) {
            return false;
        }
        // Only require fully charged attack.
        // Do NOT block on hurt window (server timing can overlap).
        if (entry.cooldown < 0.90f) {
            return false;
        }
        if (Math.abs(entry.healthBefore - previousHealth) > MAX_HEALTH_BASELINE_DRIFT) {
            return false;
        }
        long ageSeconds = Instant.now().getEpochSecond() - entry.time;
        if (ageSeconds < 0L || ageSeconds > MAX_CORRECTION_AGE_SECONDS) {
            return false;
        }
        return Math.abs(entry.predictedDamage - actualDamage) >= MIN_CORRECTION_ERROR;
    }

    private static int inferResistanceLevel(DamageLogEntry entry, float actualDamage) {
        // Reconstruct base (no resistance) damage from predicted snapshot
        float currentMultiplier = resistanceMultiplier(entry.resistance);
        if (currentMultiplier <= 0.0f) {
            currentMultiplier = 1.0f;
        }

        float noResistanceDamage = entry.predictedDamage / currentMultiplier;

        int bestLevel = entry.resistance;
        float bestError = Float.MAX_VALUE;

        for (int level = 0; level <= 5; level++) {
            float candidateDamage = noResistanceDamage * resistanceMultiplier(level);
            float error = Math.abs(candidateDamage - actualDamage);

            if (error < bestError) {
                bestError = error;
                bestLevel = level;
            }
        }

        float tolerance = Math.max(MIN_CORRECTION_ERROR, actualDamage * 0.15f);

        // Allow correction even if error slightly above tolerance but level difference is significant.
        if (bestLevel != entry.resistance && bestError <= tolerance * 1.5f) {
            return bestLevel;
        }

        return bestError <= tolerance ? bestLevel : entry.resistance;
    }

    private static float resistanceMultiplier(int level) {
        if (level <= 0) {
            return 1.0f;
        }
        return Math.max(0.0f, 1.0f - level * 0.2f);
    }
}
