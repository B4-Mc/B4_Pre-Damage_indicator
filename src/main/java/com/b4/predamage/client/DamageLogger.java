package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

/**
 * Dev-Grade Combat Snapshot Logger
 *
 * Compact CSV format.
 */
public class DamageLogger {

    private static final List<DamageLogEntry> MEMORY_CACHE = new LinkedList<>();
    private static final int MAX_CACHE_SIZE = 1000;

    private static final File LOG_DIR = new File("logs/B4-damage-logs");
    private static File sessionFile;

    public static void init() {
        if (!LOG_DIR.exists()) {
            LOG_DIR.mkdirs();
        }
        sessionFile = new File(LOG_DIR, "session-" + System.currentTimeMillis() + ".csv");
    }

    /* ============================= */
    /* ===== MAIN LOG FUNCTION ===== */
    /* ============================= */

    public static void logPrediction(
            MinecraftClient client,
            LivingEntity target,
            ItemStack weaponStack,
            float predictedDamage,
            int armor,
            int toughness,
            int resistance,
            int projectile,
            String bonus
    ) {
        if (client == null || client.player == null || client.world == null) return;
        if (target == null) return;

        if (sessionFile == null) init();

        PlayerEntity attacker = client.player;

        long time = Instant.now().getEpochSecond();

        int attackerId = attacker.getId();
        int targetId = target.getId();

        float healthBefore = target.getHealth() + target.getAbsorptionAmount();
        float healthAfter = -1f; // will be filled by reconciliation
        float actualDamage = -1f;

        int strength = attacker.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH)
                ? attacker.getStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH).getAmplifier() + 1
                : 0;

        int weakness = attacker.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WEAKNESS)
                ? attacker.getStatusEffect(net.minecraft.entity.effect.StatusEffects.WEAKNESS).getAmplifier() + 1
                : 0;

        int critical = attacker.fallDistance > 0 && !attacker.isOnGround() ? 1 : 0;

        float cooldown = attacker.getAttackCooldownProgress(0);

        int recentlyHit = target.timeUntilRegen;

        int attributeSwap = AttributeSwapTracker.detectSwap(client);

        ItemStack loggedStack = weaponStack == null ? attacker.getMainHandStack() : weaponStack;
        String ench = EnchantmentEncoder.encode(loggedStack);
        String weapon = loggedStack.getItem().toString();

        DamageLogEntry entry = new DamageLogEntry(
                time,
                attackerId,
                targetId,
                predictedDamage,
                actualDamage,
                healthBefore,
                healthAfter,
                armor,
                toughness,
                resistance,
                strength,
                weakness,
                critical,
                cooldown,
                recentlyHit,
                projectile,
                attributeSwap,
                ench,
                weapon,
                sanitizeCompactField(bonus)
        );

        addToMemory(entry);
        writeToFile(entry);
    }

    /* ============================= */

    private static void addToMemory(DamageLogEntry entry) {
        MEMORY_CACHE.add(entry);
        if (MEMORY_CACHE.size() > MAX_CACHE_SIZE) {
            MEMORY_CACHE.remove(0);
        }
    }

    private static void writeToFile(DamageLogEntry entry) {
        writeLine(entry.toString());
    }

    public static void appendReconciliation(DamageLogEntry entry) {
        if (entry == null) return;
        if (sessionFile == null) init();

        writeLine("R," +
                entry.time + "," +
                entry.attackerEntityId + "," +
                entry.targetEntityId + "," +
                entry.actualDamage + "," +
                entry.healthAfter);
    }

    public static DamageLogEntry findLatestUnreconciledForTarget(int targetEntityId) {
        for (int i = MEMORY_CACHE.size() - 1; i >= 0; i--) {
            DamageLogEntry entry = MEMORY_CACHE.get(i);
            if (entry.targetEntityId == targetEntityId && entry.actualDamage < 0) {
                return entry;
            }
        }

        return null;
    }

    private static void writeLine(String line) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sessionFile, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException ignored) {
        }
    }

    public static List<DamageLogEntry> getMemoryCache() {
        return MEMORY_CACHE;
    }

    private static String sanitizeCompactField(String value) {
        if (value == null) return "";
        return value
                .replace(',', ';')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }
}
