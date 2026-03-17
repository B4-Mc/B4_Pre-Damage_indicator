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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;

public class PreDamageHud {

    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;
    private static double maceFallStartY = -1.0;

    private static boolean wasCritMain = false;
    private static boolean wasCritOff = false;

    private static Field lastDamageField = null;
    private static boolean fieldSearched = false;

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null || client.world == null) return;

        if (client.player.isOnGround() || client.player.isClimbing() || client.player.isTouchingWater()) {
            maceFallStartY = -1.0;
        } else if (maceFallStartY == -1.0 && client.player.getVelocity().y < -0.1) {
            maceFallStartY = client.player.getY();
        }

        if (stack.isEmpty() && !isMain) {
            offDisplayed = 0.0f;
            return;
        }

        String name = stack.getItem().toString().toLowerCase();
        boolean isSpear = name.contains("spear");
        boolean isUsingThisHand = client.player.isUsingItem() && client.player.getActiveItem() == stack;
        boolean isChargedCrossbow = stack.isOf(Items.CROSSBOW) && stack.contains(DataComponentTypes.CHARGED_PROJECTILES) && !stack.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty();

        if (!isMain) {
            boolean isThrownPotion = stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
            boolean isValidOffhandWeapon = stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) ||
                    stack.isOf(Items.TRIDENT) || isSpear || isThrownPotion;

            if (!isValidOffhandWeapon) {
                offDisplayed = 0.0f;
                return;
            }

            boolean isUsingOffhand = client.player.isUsingItem() && client.player.getActiveHand() == Hand.OFF_HAND;
            if (!isUsingOffhand && !isChargedCrossbow && !isThrownPotion) {
                renderIndicator(ctx, client, 0.1F, 0xFFFFFFFF, false, "X", false);
                return;
            }
        }

        float reach = getReach(client, stack, name);
        Entity rawHit = getTarget(client, reach);

        if (rawHit instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        boolean isDragonHead = false;
        Entity target = rawHit;
        if (rawHit instanceof EnderDragonPart part) {
            isDragonHead = (part.name != null && part.name.contains("head"));
            target = part.owner;
        }

        if (!(target instanceof LivingEntity livingTarget) || target instanceof ArmorStandEntity || target instanceof CreakingEntity) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        boolean isCrit = client.player.fallDistance > 0.0F && !client.player.isOnGround() && !client.player.isClimbing() && !client.player.isTouchingWater() && !client.player.hasStatusEffect(StatusEffects.BLINDNESS) && !client.player.hasStatusEffect(StatusEffects.SLOW_FALLING) && !client.player.hasVehicle() && client.player.getAttackCooldownProgress(0.5F) > 0.9F;
        if (isSpear) isCrit = false;

        float finalValue = 0;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;
        String overrideText = null;

        boolean isSplash = stack.isOf(Items.SPLASH_POTION);
        boolean isLingering = stack.isOf(Items.LINGERING_POTION);
        boolean isExplosion = stack.isOf(Items.CROSSBOW) && hasExplosiveFirework(stack);
        boolean isActuallyProjectile = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || (stack.isOf(Items.TRIDENT) && isUsingThisHand)) && !isExplosion;

        if (isBlockedByShield(client.player, livingTarget)) {
            var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            boolean pierces = stack.isOf(Items.CROSSBOW) && EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PIERCING), stack) > 0;

            if (!pierces && !isSplash && !isLingering) {
                overrideText = "0.0";
                finalColor = 0xFF0000AA;
                finalValue = 0;
                isActuallyProjectile = false;
            }
        }

        if (overrideText == null) {
            if (isSplash || isLingering) {
                var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
                if (contents != null) {
                    boolean isUndead = livingTarget.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
                    boolean isImmuneToPoison = isUndead || livingTarget instanceof EnderDragonEntity || livingTarget instanceof WitherEntity || livingTarget.getType().equals(EntityType.BOGGED);
                    var effects = contents.potion().isPresent() ? contents.potion().get().value().getEffects() : contents.getEffects();

                    int epf = 0;
                    var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                    for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                        ItemStack piece = livingTarget.getEquippedStack(slot);
                        if (!piece.isEmpty()) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), piece);
                    }
                    float magicReduction = 1.0F - (Math.min(20.0F, (float)epf) * 0.04F);

                    for (StatusEffectInstance effect : effects) {
                        boolean isHeal = effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH);
                        boolean isHarming = effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE);
                        boolean isPoison = effect.getEffectType().equals(StatusEffects.POISON);
                        boolean isRegen = effect.getEffectType().equals(StatusEffects.REGENERATION);
                        String heartStr = effect.getAmplifier() > 0 ? "💕" : "❤"; // FIXED: Uses stable heart character

                        if (livingTarget instanceof EndermanEntity && isHarming) {
                            overrideText = null; finalValue = 0; continue;
                        }

                        if (isPoison) {
                            if (!isImmuneToPoison) { overrideText = "-" + heartStr; finalColor = 0xFF4E9331; }
                        } else if (isRegen) {
                            if (!isUndead && !(livingTarget instanceof EnderDragonEntity)) { overrideText = "+" + heartStr; finalColor = 0xFF00FF00; }
                        } else if (isLingering) {
                            if (isHeal) { overrideText = isUndead ? "-" + heartStr : "+" + heartStr; finalColor = isUndead ? 0xFF990000 : 0xFF00FF00; }
                            else if (isHarming) { overrideText = isUndead ? "+" + heartStr : "-" + heartStr; finalColor = isUndead ? 0xFF00FF00 : 0xFF990000; }
                        } else if (isSplash) {
                            // FIXED: Protection reduction is applied only to damage (Issue 1), never to healing
                            if (isHeal) {
                                float val = (isUndead ? 6.0f : 4.0f) * (effect.getAmplifier() + 1);
                                isHealing = !isUndead;
                                finalValue = isHealing ? val : val * magicReduction;
                            } else if (isHarming) {
                                float val = (isUndead ? 4.0f : 6.0f) * (effect.getAmplifier() + 1);
                                isHealing = isUndead;
                                finalValue = isHealing ? val : val * magicReduction;
                            }
                        }
                    }
                }
            } else if (stack.isOf(Items.TRIDENT)) {
                float phys = calculateRawPhysical(client, livingTarget, stack, name, isCrit);
                phys = isUsingThisHand ? 8.0f : phys;
                finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, isUsingThisHand, isExplosion, false);
            } else if (isSpear && isUsingThisHand) {
                Vec3d vel = client.player.getVelocity();
                if (client.player.getVehicle() != null) vel = client.player.getVehicle().getVelocity();
                Vec3d relativeVel = vel.subtract(livingTarget.getVelocity());
                double speedBps = relativeVel.length() * 20.0;
                float phys = (float) ((name.contains("netherite") ? 8.0f : 5.0f) + (speedBps * 0.8f));
                finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, false, false, false);
            } else {
                float phys = calculateRawPhysical(client, livingTarget, stack, name, isCrit);
                float magic = calculateMagicCap(client.player, livingTarget, stack, isUsingThisHand || isChargedCrossbow);
                boolean isUndead = livingTarget.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);

                // Determine if the magic portion is actually healing the target
                isHealing = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) && isMagicHealing(stack, client.player, isUndead);
                finalValue = applyFinalReductions(client, livingTarget, stack, phys, magic, isActuallyProjectile, isExplosion, isHealing);
            }
        }

        // Dragon head logic
        if (livingTarget instanceof EnderDragonEntity dragon) {
            if (!isHealing && overrideText == null && finalValue > 0) {
                if (!isDragonHead) finalValue = (finalValue / 4.0f) + 1.0f;
            }
            int phaseId = dragon.getDataTracker().get(EnderDragonEntity.PHASE_TYPE);
            if (phaseId >= 4 && phaseId <= 8 && isActuallyProjectile && overrideText == null) finalValue = 0;
        }

        if (livingTarget.timeUntilRegen > 10 && !isHealing && overrideText == null) {
            float od = getLastDamageTaken(livingTarget);
            finalValue = Math.max(0, finalValue - od);
        }

        if (isSplash && overrideText == null) {
            float effectiveHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
            finalValue = isHealing ? Math.min(finalValue, livingTarget.getMaxHealth() - livingTarget.getHealth()) : Math.min(finalValue, effectiveHealth);
        }

        if (livingTarget instanceof WolfEntity wolf && !wolf.getBodyArmor().isEmpty() && !isSplash && !isLingering) finalValue = 0;
        if (livingTarget instanceof EndermanEntity && isActuallyProjectile && overrideText == null) finalValue = 0;
        if (livingTarget instanceof WitherEntity wither && wither.getHealth() <= wither.getMaxHealth() / 2.0f && isActuallyProjectile && overrideText == null) finalValue = 0;

        if (overrideText == null) finalColor = getColor(livingTarget, finalValue, stack, isHealing);

        String suffix = "";
        // FIXED: Exclude projectiles and explosions from showing the melee crit "^" suffix
        if (isCrit && !isActuallyProjectile && !isExplosion && !isSpear && !isSplash && !isLingering) suffix = "^";
        else if ((isUsingThisHand && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || isSpear)) || isChargedCrossbow) suffix = "*";

        boolean noSmoothing = isSplash || isLingering || isCrit || (isMain ? wasCritMain : wasCritOff);
        if (isMain) wasCritMain = isCrit; else wasCritOff = isCrit;

        if (isMain) {
            mainTarget = finalValue;
            if (noSmoothing || overrideText != null) mainDisplayed = mainTarget;
            else mainDisplayed = (Math.abs(mainDisplayed - mainTarget) < 0.05F) ? mainTarget : MathHelper.lerp(0.15F, mainDisplayed, mainTarget);

            if (mainDisplayed >= 0.1F || overrideText != null) {
                if (overrideText != null) renderString(ctx, client, overrideText, finalColor, true);
                else renderIndicator(ctx, client, mainDisplayed, finalColor, true, suffix, isHealing);
            } else mainDisplayed = 0;
        } else {
            if (finalValue > 0 || overrideText != null || offDisplayed >= 0.1F) {
                if (noSmoothing || overrideText != null) offDisplayed = finalValue;
                else offDisplayed = (Math.abs(offDisplayed - finalValue) < 0.05F) ? finalValue : MathHelper.lerp(0.20F, offDisplayed, finalValue);

                if (offDisplayed >= 0.1F || overrideText != null) {
                    if (overrideText != null) renderString(ctx, client, overrideText, finalColor, false);
                    else renderIndicator(ctx, client, offDisplayed, finalColor, false, suffix, isHealing);
                } else offDisplayed = 0;
            }
        }
    }

    private static float calculateRawPhysical(MinecraftClient c, LivingEntity target, ItemStack s, String n, boolean isCrit) {
        PlayerEntity p = c.player;
        if (s.isOf(Items.BOW)) {
            if (!(p.isUsingItem() && p.getActiveItem() == s)) return 0.0F;
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
                    return (fw != null && !fw.explosions().isEmpty()) ? 5.0F + (fw.explosions().size() * 2.0F) : 0.0F;
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
        float strength = p.hasStatusEffect(StatusEffects.STRENGTH) ? 3.0F * (p.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1) : 0;
        float weakness = p.hasStatusEffect(StatusEffects.WEAKNESS) ? -4.0F : 0;
        float total = (float) baseAttr + strength + weakness;

        if (s.isOf(Items.MACE)) {
            float fallDist = (float) (maceFallStartY != -1.0 ? Math.max(0, maceFallStartY - p.getY()) : 0);
            if (fallDist > 1.5F && !p.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
                float smashBonus = (fallDist <= 3.0F) ? fallDist * 4.0F : (fallDist <= 8.0F ? 12.0F + ((fallDist - 3.0F) * 2.0F) : 22.0F + ((fallDist - 8.0F) * 1.0F));
                var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                smashBonus += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.DENSITY), s) * 0.5F * fallDist;
                total += smashBonus;
                isCrit = true;
            }
        }
        return isCrit ? total * 1.5F : total;
    }

    private static float applyFinalReductions(MinecraftClient client, LivingEntity t, ItemStack s, float phys, float magic, boolean isProj, boolean isExplo, boolean isHealing) {
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        float extra = 0;
        int sharp = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SHARPNESS), s);
        if (sharp > 0) extra += 0.5F * sharp + 0.5F;
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) extra += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SMITE), s) * 2.5F;

        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), s);
        if (breach > 0) { armor *= (1.0F - breach * 0.15F); toughness *= (1.0F - breach * 0.15F); }

        float afterArmor = DamageUtil.getDamageLeft(t, phys + extra, t.getDamageSources().generic(), armor, toughness);

        int epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack p = t.getEquippedStack(slot);
            if (!p.isEmpty()) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), p);
                if (isProj) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), p) * 2;
                if (isExplo) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), p) * 2;
            }
        }
        float protRed = (1.0F - (Math.min(20.0F, (float)epf) * 0.04F));

        // FIXED: Damage gets reduced by protection (Issue 1), but Healing II never does.
        return isHealing ? (afterArmor * protRed) + magic : (afterArmor + magic) * protRed;
    }

    private static float getReach(MinecraftClient c, ItemStack s, String n) {
        if (c.player == null) return 3.5F;
        float base = (float) c.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        if (s.isOf(Items.MACE) && c.player.fallDistance > 0) return 100F;
        if (s.isOf(Items.BOW) || s.isOf(Items.TRIDENT) || s.isOf(Items.CROSSBOW)) return (c.player.isUsingItem() && c.player.getActiveItem() == s) ? 45F : base;
        return n.contains("spear") ? base + 1.5F : base;
    }

    private static Entity getTarget(MinecraftClient client, double maxDist) {
        Entity cam = client.getCameraEntity();
        if (cam == null || client.world == null) return null;
        if (maxDist <= 10.0 && client.crosshairTarget instanceof EntityHitResult ehr) return ehr.getEntity() instanceof EnderDragonPart p ? p.owner : ehr.getEntity();
        Vec3d start = cam.getEyePos(), rot = cam.getRotationVec(1.0F), end = start.add(rot.multiply(maxDist));
        EntityHitResult eHit = ProjectileUtil.raycast(cam, start, end, cam.getBoundingBox().stretch(rot.multiply(maxDist)).expand(1.0D), e -> !e.isSpectator() && e.canHit(), maxDist * maxDist);
        return eHit != null ? eHit.getEntity() : null;
    }

    private static boolean isBlockedByShield(Entity attacker, LivingEntity target) {
        if (!target.isBlocking()) return false;
        Vec3d diff = new Vec3d(attacker.getX() - target.getX(), 0, attacker.getZ() - target.getZ()).normalize();
        Vec3d look = target.getRotationVec(1.0F);
        return diff.dotProduct(new Vec3d(look.x, 0, look.z).normalize()) > 0.0;
    }

    private static boolean isMagicHealing(ItemStack bow, PlayerEntity p, boolean targetIsUndead) {
        ItemStack proj = bow.isOf(Items.BOW) ? getActiveArrow(p) : (bow.get(DataComponentTypes.CHARGED_PROJECTILES) != null && !bow.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty() ? bow.get(DataComponentTypes.CHARGED_PROJECTILES).getProjectiles().get(0) : ItemStack.EMPTY);
        if (proj.isEmpty() || !proj.contains(DataComponentTypes.POTION_CONTENTS)) return false;
        for (StatusEffectInstance e : proj.get(DataComponentTypes.POTION_CONTENTS).getEffects()) {
            if (e.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && !targetIsUndead) return true;
            if (e.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && targetIsUndead) return true;
        }
        return false;
    }

    private static float calculateMagicCap(PlayerEntity p, LivingEntity target, ItemStack bow, boolean active) {
        if (!active) return 0;
        ItemStack proj = bow.isOf(Items.BOW) ? getActiveArrow(p) : (bow.get(DataComponentTypes.CHARGED_PROJECTILES) != null && !bow.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty() ? bow.get(DataComponentTypes.CHARGED_PROJECTILES).getProjectiles().get(0) : ItemStack.EMPTY);
        if (proj.isEmpty() || !proj.contains(DataComponentTypes.POTION_CONTENTS)) return 0;
        boolean undead = target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
        for (StatusEffectInstance e : proj.get(DataComponentTypes.POTION_CONTENTS).getEffects()) {
            if ((e.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && !undead) || (e.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && undead)) return 6.0F * (e.getAmplifier() + 1);
            if ((e.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && !undead) || (e.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && undead)) return 6.0F * (e.getAmplifier() + 1);
        }
        return 0;
    }

    private static ItemStack getActiveArrow(PlayerEntity p) {
        if (p.getOffHandStack().getItem() instanceof ArrowItem) return p.getOffHandStack();
        if (p.getMainHandStack().getItem() instanceof ArrowItem) return p.getMainHandStack();
        for (int i = 0; i < p.getInventory().size(); i++) if (p.getInventory().getStack(i).getItem() instanceof ArrowItem) return p.getInventory().getStack(i);
        return ItemStack.EMPTY;
    }

    private static float applyPowerEnch(MinecraftClient c, ItemStack s, float base) {
        var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int lvl = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.POWER), s);
        return lvl > 0 ? base * (1.25F + (0.25F * lvl)) : base;
    }

    private static boolean hasExplosiveFirework(ItemStack stack) {
        ChargedProjectilesComponent c = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        return c != null && !c.isEmpty() && c.getProjectiles().get(0).isOf(Items.FIREWORK_ROCKET) && c.getProjectiles().get(0).contains(DataComponentTypes.FIREWORKS) && !c.getProjectiles().get(0).get(DataComponentTypes.FIREWORKS).explosions().isEmpty();
    }

    private static int getColor(LivingEntity t, float dmg, ItemStack s, boolean heal) {
        float health = t.getHealth() + t.getAbsorptionAmount();
        if (s.isOf(Items.SPLASH_POTION)) return heal ? 0xFF00FF00 : 0xFF990000;
        return (dmg >= health) ? (isEnemyHoldingTotem(t) ? 0xFFF2FF00 : 0xFFFF0000) : 0xFFFFFFFF;
    }

    private static boolean isEnemyHoldingTotem(LivingEntity e) { return e.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING) || e.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING); }

    private static void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, String suffix, boolean isHealing) {
        if (!suffix.equals("X") && dmg < 0.1F) return;
        renderString(ctx, c, (suffix.equals("X") ? "X" : (isHealing ? "+" : "") + String.format("%.1f", dmg) + suffix), color, isMain);
    }

    private static void renderString(DrawContext ctx, MinecraftClient c, String txt, int color, boolean isMain) {
        int centerX = c.getWindow().getScaledWidth() / 2, centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        ctx.drawTextWithShadow(c.textRenderer, txt, isMain ? centerX + 15 : centerX - 15 - c.textRenderer.getWidth(txt), centerY, color);
    }

    private static float getLastDamageTaken(LivingEntity entity) {
        if (!fieldSearched) {
            for (String m : new String[]{"lastDamageTaken", "field_6235", "lastDamage"}) {
                try { lastDamageField = LivingEntity.class.getDeclaredField(m); lastDamageField.setAccessible(true); break; } catch (Exception ignored) {}
            }
            fieldSearched = true;
        }
        try { return lastDamageField != null ? lastDamageField.getFloat(entity) : 0; } catch (Exception e) { return 0; }
    }
}