package com.b4.predamage.client;

import java.util.UUID;

/**
 * Dev-Grade Compact Combat Snapshot Entry
 *
 * Compact CSV Format (no labels, fixed order):
 *
 * time,attId,tgtId,pred,act,hpBefore,hpAfter,armor,tough,res,str,weak,crit,cd,recent,proj,swap,ench,weapon,bonus
 *
 * All numeric where possible for compression efficiency.
 */
public class DamageLogEntry {

    /* === CORE IDENTIFICATION === */
    public final long time;                 // epoch seconds
    public final int attackerEntityId;      // compact entity id
    public final int targetEntityId;

    /* === DAMAGE === */
    public final float predictedDamage;
    public float actualDamage;              // updated later by reconciliation

    /* === HEALTH === */
    public final float healthBefore;
    public float healthAfter;

    /* === DEFENSE === */
    public final int armor;
    public final int toughness;
    public final int resistance;

    /* === OFFENSE MODIFIERS === */
    public final int strength;
    public final int weakness;
    public final int critical;              // 0/1
    public final float cooldown;            // attack cooldown progress

    /* === TARGET STATE === */
    public final int recentlyHitTimer;

    /* === CONTEXT FLAGS === */
    public final int projectile;            // 0=melee,1=arrow,2=trident,...
    public final int attributeSwap;         // 0/1 swap detected

    /* === COMPACT STRINGS === */
    public final String enchantments;       // compact encoding (e.g. SH5,SM3)
    public final String weapon;             // compact weapon id
    public final String bonus;              // smash/crit/etc flags

    public DamageLogEntry(
            long time,
            int attackerEntityId,
            int targetEntityId,
            float predictedDamage,
            float actualDamage,
            float healthBefore,
            float healthAfter,
            int armor,
            int toughness,
            int resistance,
            int strength,
            int weakness,
            int critical,
            float cooldown,
            int recentlyHitTimer,
            int projectile,
            int attributeSwap,
            String enchantments,
            String weapon,
            String bonus
    ) {
        this.time = time;
        this.attackerEntityId = attackerEntityId;
        this.targetEntityId = targetEntityId;
        this.predictedDamage = predictedDamage;
        this.actualDamage = actualDamage;
        this.healthBefore = healthBefore;
        this.healthAfter = healthAfter;
        this.armor = armor;
        this.toughness = toughness;
        this.resistance = resistance;
        this.strength = strength;
        this.weakness = weakness;
        this.critical = critical;
        this.cooldown = cooldown;
        this.recentlyHitTimer = recentlyHitTimer;
        this.projectile = projectile;
        this.attributeSwap = attributeSwap;
        this.enchantments = enchantments;
        this.weapon = weapon;
        this.bonus = bonus;
    }

    /**
     * Compact CSV (high compression friendly).
     */
    @Override
    public String toString() {
        return time + "," +
                attackerEntityId + "," +
                targetEntityId + "," +
                predictedDamage + "," +
                actualDamage + "," +
                healthBefore + "," +
                healthAfter + "," +
                armor + "," +
                toughness + "," +
                resistance + "," +
                strength + "," +
                weakness + "," +
                critical + "," +
                cooldown + "," +
                recentlyHitTimer + "," +
                projectile + "," +
                attributeSwap + "," +
                enchantments + "," +
                weapon + "," +
                bonus;
    }
}