package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Heal Logger Subsystem
 *
 * Detects positive health delta and logs healing events.
 */
public class HealLogger {

    private static final Map<Integer, Float> LAST_HEALTH = new HashMap<>();
    private static File healFile;

    public static void init() {
        File dir = new File("logs/B4-damage-logs");
        if (!dir.exists()) dir.mkdirs();

        healFile = new File(dir, "session-" + System.currentTimeMillis() + "-heal.csv");
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null) return;

        if (healFile == null) init();

        for (var entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;

            int id = living.getId();
            float currentHealth = living.getHealth() + living.getAbsorptionAmount();

            if (LAST_HEALTH.containsKey(id)) {
                float previous = LAST_HEALTH.get(id);

                if (currentHealth > previous) {
                    float delta = currentHealth - previous;

                    String source = detectSource(living);

                    HealLogEntry entry = new HealLogEntry(
                            Instant.now().getEpochSecond(),
                            id,
                            delta,
                            currentHealth,
                            source
                    );

                    write(entry);
                }
            }

            LAST_HEALTH.put(id, currentHealth);
        }
    }

    private static String detectSource(LivingEntity living) {
        if (living.hasStatusEffect(StatusEffects.REGENERATION)) return "REGEN";
        if (living.hasStatusEffect(StatusEffects.INSTANT_HEALTH)) return "INSTANT";
        if (living.hasStatusEffect(StatusEffects.ABSORPTION)) return "ABSORB";
        return "UNKNOWN";
    }

    private static void write(HealLogEntry entry) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(healFile, true))) {
            writer.write(entry.toString());
            writer.newLine();
        } catch (Exception ignored) {
        }
    }
}