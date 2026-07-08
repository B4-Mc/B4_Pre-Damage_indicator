package com.b4.predamage.client;

import java.util.UUID;

/**
 * Represents cached resistance inference for a single entity.
 */
public class ResistanceState {

    public enum SourceType {
        DIRECT,
        SPLASH,
        LINGERING,
        BEACON,
        GOLDEN_APPLE,
        INFERRED,
        LOGGER_CORRECTED,
        NONE
    }

    private final UUID entityId;

    private int level;
    private SourceType source;
    private long lastUpdated;
    private long expiresAt;
    private float confidence;

    public ResistanceState(UUID entityId) {
        this.entityId = entityId;
        this.level = 0;
        this.source = SourceType.NONE;
        this.lastUpdated = 0L;
        this.expiresAt = 0L;
        this.confidence = 0.0f;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public int getLevel() {
        return level;
    }

    public SourceType getSource() {
        return source;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public float getConfidence() {
        return confidence;
    }

    public void update(int level, SourceType source, float confidence) {
        update(level, source, confidence, 0L);
    }

    public void update(int level, SourceType source, float confidence, long durationMs) {
        this.level = level;
        this.source = source;
        this.confidence = confidence;
        this.lastUpdated = System.currentTimeMillis();
        this.expiresAt = durationMs > 0L ? this.lastUpdated + durationMs : 0L;
    }

    public boolean isExpired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    public boolean isUsable(long now, float minConfidence) {
        return level > 0 && confidence >= minConfidence && !isExpired(now);
    }

    public void clear() {
        this.level = 0;
        this.source = SourceType.NONE;
        this.confidence = 0.0f;
        this.lastUpdated = 0L;
        this.expiresAt = 0L;
    }
}
