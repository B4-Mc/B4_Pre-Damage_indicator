package com.b4.predamage.client;

/**
 * Compact Heal Log Entry
 *
 * CSV Format:
 * time,entityId,healAmount,healthAfter,source
 */
public class HealLogEntry {

    public final long time;
    public final int entityId;
    public final float healAmount;
    public final float healthAfter;
    public final String source;

    public HealLogEntry(
            long time,
            int entityId,
            float healAmount,
            float healthAfter,
            String source
    ) {
        this.time = time;
        this.entityId = entityId;
        this.healAmount = healAmount;
        this.healthAfter = healthAfter;
        this.source = source;
    }

    @Override
    public String toString() {
        return time + "," +
                entityId + "," +
                healAmount + "," +
                healthAfter + "," +
                source;
    }
}