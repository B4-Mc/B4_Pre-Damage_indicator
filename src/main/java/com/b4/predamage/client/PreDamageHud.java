package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreakingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class PreDamageHud {

    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;
    private static double maceFallStartY = -1.0;

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null || client.world == null) return;

        if (client.player.isOnGround()) {
            maceFallStartY = -1.0;
        } else if (maceFallStartY == -1.0 && client.player.getVelocity().y < -0.1) {
            maceFallStartY = client.player.getY();
        }

        if (!isMain && stack.isEmpty()) {
            offDisplayed = 0.0f;
            return;
        }

        String name = stack.getItem().toString().toLowerCase();
        boolean isSpear = name.contains("spear");
        boolean isUsingThisHand = client.player.isUsingItem() && client.player.getActiveItem() == stack;
        float reach = getReach(client, stack, name);
        Entity target = getTarget(client, reach);

        if (!(target instanceof LivingEntity livingTarget) || target instanceof ArmorStandEntity) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        if (livingTarget instanceof CreakingEntity) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        // Standard Crit Detection (Falling, not on ground, not in water, etc.)
        boolean isCrit = client.player.fallDistance > 0.0F
                && !client.player.isOnGround()
                && !client.player.isClimbing()
                && !client.player.isTouchingWater()
                && !client.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !client.player.hasVehicle();

        // SPEAR FIX: Spears cannot crit. If it's a spear, we force isCrit to false.
        if (isSpear) {
            isCrit = false;
        }

        float finalValue = 0;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;
        float dragonMultiplier = 1.0f;

        boolean isActuallyProjectile = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || (stack.isOf(Items.TRIDENT) && isUsingThisHand));

        if (target instanceof EnderDragonPart part) {
            if (part.name != null && part.name.contains("head")) dragonMultiplier = 4.0f;
            target = part.owner;
        }

        if (stack.isOf(Items.SPLASH_POTION)) {
            var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents != null) {
                boolean isUndead = livingTarget.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
                var effects = contents.potion().isPresent() ? contents.potion().get().value().getEffects() : contents.getEffects();
                for (StatusEffectInstance effect : effects) {
                    if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) {
                        finalValue = 4.0f * (float) Math.pow(2, effect.getAmplifier());
                        isHealing = !isUndead;
                    } else if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE)) {
                        finalValue = 6.0f * (float) Math.pow(2, effect.getAmplifier());
                        isHealing = isUndead;
                    }
                }
                if (livingTarget instanceof EnderDragonEntity) finalValue = 0;
            }
        } else if (isSpear && isUsingThisHand) {
            Vec3d playerVel = client.player.getVelocity();
            if (client.player.getVehicle() != null) playerVel = client.player.getVehicle().getVelocity();
            Vec3d relativeVel = playerVel.subtract(target.getVelocity());
            double speedBps = relativeVel.length() * 20.0;
            float base = name.contains("netherite") ? 8.0f : 5.0f;
            float raw = (float) (base + (speedBps * 0.8f));
            finalValue = applyFinalReductions(client, livingTarget, stack, raw, 0, false, false);
        } else if (stack.isOf(Items.MACE) && !client.player.isOnGround()) {
            float fallDist = (float) (maceFallStartY != -1.0 ? Math.max(0, maceFallStartY - client.player.getY()) : 0);
            if (client.player.hasStatusEffect(StatusEffects.SLOW_FALLING)) fallDist = 0;
            finalValue = applyFinalReductions(client, livingTarget, stack, calculateMaceDamage(client, stack, fallDist), 0, false, isCrit);
        } else {
            float phys = calculateRawPhysical(client, client.player, stack, name);
            float mag = calculateMagicBonus(client.player, stack, livingTarget);
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, mag, isActuallyProjectile, isCrit && !isActuallyProjectile);
        }

        // Immunity Checks
        if (livingTarget instanceof WolfEntity wolf && !wolf.getBodyArmor().isEmpty()) {
            if (!stack.isOf(Items.SPLASH_POTION)) finalValue = 0;
        }
        if (livingTarget instanceof EndermanEntity && isActuallyProjectile) finalValue = 0;
        if (livingTarget instanceof WitherEntity wither && wither.getHealth() <= wither.getMaxHealth() / 2.0f) {
            if (isActuallyProjectile) finalValue = 0;
        }
        if (livingTarget instanceof EnderDragonEntity) {
            finalValue = (dragonMultiplier == 4.0f) ? finalValue : (finalValue * 0.25f) + 1.0f;
            int phaseId = livingTarget.getDataTracker().get(EnderDragonEntity.PHASE_TYPE);
            if ((phaseId >= 4 && phaseId <= 7) && isActuallyProjectile) finalValue = 0;
        }

        finalColor = getColor(livingTarget, finalValue, stack, isHealing);
        // showCritMarker handles the asterisk. Force it to false for spears.
        boolean showCritMarker = isCrit && !isActuallyProjectile && !isSpear;
        boolean useAsterisk = (stack.isOf(Items.BOW) && isUsingThisHand) || (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) || showCritMarker;

        if (isMain) {
            handleMainSmoothing(finalValue);
            renderIndicator(ctx, client, mainDisplayed, finalColor, true, useAsterisk, isHealing);
        } else if (finalValue > 0) {
            offDisplayed = MathHelper.lerp(0.20F, offDisplayed, finalValue);
            renderIndicator(ctx, client, offDisplayed, finalColor, false, useAsterisk, isHealing);
        }
    }

    private static float applyFinalReductions(MinecraftClient client, LivingEntity t, ItemStack s, float phys, float magic, boolean isProj, boolean isCrit) {
        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        if (isCrit) {
            phys *= 1.5F;
        }

        float extra = 0;
        int sharp = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SHARPNESS), s);
        if (sharp > 0) extra += 0.5F * sharp + 0.5F;

        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
            int smite = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SMITE), s);
            extra += smite * 2.5F;
        }
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            int bane = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BANE_OF_ARTHROPODS), s);
            extra += bane * 2.5F;
        }
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            int imp = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.IMPALING), s);
            extra += imp * 2.5F;
        }

        float totalBase = phys + extra;
        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), s);
        if (breach > 0) armor *= (1.0F - (breach * 0.15F));

        float afterArmor = DamageUtil.getDamageLeft(t, totalBase, t.getDamageSources().generic(), armor, toughness);
        return afterArmor + magic;
    }

    private static float calculateMaceDamage(MinecraftClient client, ItemStack s, float fallDist) {
        float total = 6.0f;
        if (fallDist > 1.5F) {
            total += (fallDist * 3.0F);
            var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            int density = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.DENSITY), s);
            total += (density * 0.5f * fallDist);
        }
        return total;
    }

    private static float calculateRawPhysical(MinecraftClient c, PlayerEntity p, ItemStack s, String n) {
        if (s.isOf(Items.BOW)) {
            if (!p.isUsingItem() || p.getActiveItem() != s) return 0.0F;
            int ticks = s.getMaxUseTime(p) - p.getItemUseTimeLeft();
            float pull = Math.min((float) ticks / 20.0F, 1.0F);
            pull = (pull * pull + pull * 2.0F) / 3.0F;
            return applyPowerEnch(c, s, (pull * 7.0F) + 2.0F);
        }
        if (s.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent charged = s.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged != null && !charged.isEmpty()) {
                ItemStack proj = charged.getProjectiles().get(0);
                if (proj.isOf(Items.FIREWORK_ROCKET)) {
                    FireworksComponent fw = proj.get(DataComponentTypes.FIREWORKS);
                    return fw != null ? 5.0F + (fw.explosions().size() * 2.0F) : 0.0F;
                }
                return applyPowerEnch(c, s, 9.0F);
            }
            return 0.0F;
        }
        double baseAttr = p.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE);
        AttributeModifiersComponent mods = s.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (var entry : mods.modifiers()) {
                if (entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) baseAttr += entry.modifier().value();
            }
        }
        return (float) baseAttr;
    }

    private static float applyPowerEnch(MinecraftClient c, ItemStack s, float base) {
        var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int lvl = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.POWER), s);
        return lvl > 0 ? base * (1.25F + (0.25F * lvl)) : base;
    }

    private static float calculateMagicBonus(PlayerEntity p, ItemStack s, LivingEntity t) {
        ItemStack projectile = ItemStack.EMPTY;
        if (s.isOf(Items.BOW)) {
            for (int i = 0; i < p.getInventory().size(); i++) {
                ItemStack invStack = p.getInventory().getStack(i);
                if (invStack.getItem() instanceof ArrowItem) { projectile = invStack; break; }
            }
        } else if (s.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent c = s.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (c != null && !c.isEmpty()) projectile = c.getProjectiles().get(0);
        }
        if (projectile.isEmpty() || !projectile.contains(DataComponentTypes.POTION_CONTENTS)) return 0;
        PotionContentsComponent contents = projectile.get(DataComponentTypes.POTION_CONTENTS);
        boolean isUndead = t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
        float magic = 0;
        for (StatusEffectInstance effect : contents.getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE)) magic += isUndead ? 0 : 6.0F * (effect.getAmplifier() + 1);
            else if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) magic += isUndead ? 6.0F * (effect.getAmplifier() + 1) : 0;
        }
        return magic;
    }

    private static void handleMainSmoothing(float actual) {
        mainTarget = actual;
        if (Math.abs(mainDisplayed - mainTarget) < 0.05F) mainDisplayed = mainTarget;
        else mainDisplayed = MathHelper.lerp(0.15F, mainDisplayed, mainTarget);
    }

    private static float getReach(MinecraftClient c, ItemStack s, String n) {
        if (c.player == null) return 3.5F;
        boolean using = c.player.isUsingItem() && c.player.getActiveItem() == s;
        if (s.isOf(Items.MACE) && c.player.fallDistance > 0.0f) return 100.0F;
        if (n.contains("spear") && using) return 100.0F;
        if ((s.isOf(Items.BOW) || s.isOf(Items.TRIDENT)) && using) return 30.0F;
        return n.contains("spear") ? 4.5F : 3.5F;
    }

    private static Entity getTarget(MinecraftClient c, float r) {
        Entity cam = c.getCameraEntity();
        if (cam == null || c.world == null) return null;
        Vec3d start = cam.getCameraPosVec(1.0F);
        Vec3d rot = cam.getRotationVec(1.0F);
        Vec3d end = start.add(rot.multiply(r));
        BlockHitResult blockHit = c.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, cam));
        double maxDist = blockHit.getType() != HitResult.Type.MISS ? blockHit.getPos().squaredDistanceTo(start) : r * r;
        EntityHitResult entityHit = ProjectileUtil.raycast(cam, start, end, cam.getBoundingBox().stretch(rot.multiply(r)).expand(1.0D), (ent) -> !ent.isSpectator() && ent.canHit(), maxDist);
        return entityHit != null ? entityHit.getEntity() : null;
    }

    private static int getColor(LivingEntity target, float damage, ItemStack stack, boolean isHealing) {
        if (stack.isOf(Items.SPLASH_POTION)) {
            if (!isHealing && isEnemyHoldingTotem(target) && damage >= target.getHealth()) return 0xFFF2FF00;
            return isHealing ? 0xFF00FF00 : 0xFF990000;
        }
        if (damage >= target.getHealth()) {
            return isEnemyHoldingTotem(target) ? 0xFFF2FF00 : 0xFFFF0000;
        }
        return 0xFFFFFFFF;
    }

    private static boolean isEnemyHoldingTotem(LivingEntity e) {
        return e.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING) || e.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, boolean useAsterisk, boolean isHealing) {
        if (dmg < 0.1F) return;
        String txt = (isHealing ? "+" : "") + String.format("%.1f", dmg) + (useAsterisk ? "*" : "");
        int centerX = c.getWindow().getScaledWidth() / 2;
        int centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 15 : centerX - 15 - c.textRenderer.getWidth(txt);
        ctx.drawTextWithShadow(c.textRenderer, txt, x, centerY, color);
    }
}