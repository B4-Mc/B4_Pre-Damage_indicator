package com.b4.predamage.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.damage.DamageSource;

public class DamageCalculator {

    public static float getPredictedDamage(PlayerEntity player, Entity target, boolean isOffhand, boolean isSecondaryAction) {
        if (!(target instanceof LivingEntity livingTarget)) return 0;

        ItemStack stack = isOffhand ? player.getOffHandStack() : player.getMainHandStack();

        // 1.21.11 Mapping fix: Attribute is ATTACK_DAMAGE
        float damage = (float) player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);

        DamageSource source = player.getDamageSources().playerAttack(player);

        // Mace Logic (Casting fallDistance to float)
        if (stack.getItem() instanceof MaceItem) {
            float fallDist = (float) player.fallDistance; // FIXED: Lossy conversion fix
            if (fallDist > 1.5F) {
                float maceBonus = (fallDist <= 3) ? (fallDist * 4) : (fallDist <= 8 ? 12 + (fallDist - 3) * 2 : 22 + (fallDist - 8));
                damage += maceBonus;
            }
        }

        // Spear Logic (1.21.11 Mounts of Mayhem)
        if (stack.getItem().getTranslationKey().contains("spear")) {
            if (isSecondaryAction) {
                // Simplified velocity check for the preview
                double relativeVel = player.getVelocity().length();
                damage *= (float) (1.0 + (relativeVel * 1.5));
            } else {
                damage *= 0.7F; // Jab modifier
            }
        }

        // Buffs
        if (player.hasStatusEffect(StatusEffects.STRENGTH)) {
            damage += 3 * (player.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1);
        }

        // Critical Check (No slow falling = can crit)
        if (player.fallDistance > 0.0F && !player.isOnGround() && !player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
            damage *= 1.5F;
        }

        // Output Damage (Armor Simulation)
        // 1.21.11 DamageUtil requires: (LivingEntity, rawDamage, damageSource, armor, toughness)
        damage = DamageUtil.getDamageLeft(
                livingTarget,
                damage,
                source,
                (float) livingTarget.getArmor(),
                (float) livingTarget.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS)
        );

        return Math.max(0, damage);
    }
}