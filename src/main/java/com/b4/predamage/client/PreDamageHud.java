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

    private static Field lastDamageField = null;
    private static boolean fieldSearched = false;

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null || client.world == null) return;

        // TRACKING MACE FALL START
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

        // OFF-HAND Weapon Visibility Logic
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

        // REACH & TARGET DETECTION
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
        // Firework explosions are strictly explosions, not standard projectiles
        boolean isActuallyProjectile = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || (stack.isOf(Items.TRIDENT) && isUsingThisHand)) && !isExplosion;

        // POTION / TRIDENT / SPEAR / MELEE CALCULATION
        if (isSplash || isLingering) {
            var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents != null) {
                boolean isUndead = livingTarget.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
                boolean isImmuneToPoison = isUndead || livingTarget instanceof EnderDragonEntity || livingTarget instanceof WitherEntity || livingTarget.getType().equals(EntityType.BOGGED);
                var effects = contents.potion().isPresent() ? contents.potion().get().value().getEffects() : contents.getEffects();

                for (StatusEffectInstance effect : effects) {
                    boolean isHeal = effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH);
                    boolean isHarming = effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE);
                    boolean isPoison = effect.getEffectType().equals(StatusEffects.POISON);
                    boolean isRegen = effect.getEffectType().equals(StatusEffects.REGENERATION);

                    String heartStr = effect.getAmplifier() > 0 ? "❤❤" : "❤";

                    // Enderman Harming immunity
                    if (livingTarget instanceof EndermanEntity && isHarming) {
                        overrideText = null; finalValue = 0; continue;
                    }

                    if (isPoison) {
                        if (!isImmuneToPoison) {
                            overrideText = "-" + heartStr; finalColor = 0xFF4E9331;
                        } else {
                            // Poison shows absolutely nothing against immune mobs (Dragon/Wither/Undead)
                            overrideText = null; finalValue = 0; continue;
                        }
                    } else if (isRegen) {
                        // Regen ignores Dragon and Undead
                        if (!isUndead && !(livingTarget instanceof EnderDragonEntity)) {
                            overrideText = "+" + heartStr; finalColor = 0xFF00FF00;
                        }
                    } else if (isLingering) {
                        if (isHeal) { overrideText = isUndead ? "-" + heartStr : "+" + heartStr; finalColor = isUndead ? 0xFF990000 : 0xFF00FF00; }
                        else if (isHarming) { overrideText = isUndead ? "+" + heartStr : "-" + heartStr; finalColor = isUndead ? 0xFF00FF00 : 0xFF990000; }
                    } else if (isSplash) {
                        if (isHeal) { finalValue = isUndead ? 6.0f * (effect.getAmplifier() + 1) : 4.0f * (effect.getAmplifier() + 1); isHealing = !isUndead; }
                        else if (isHarming) { finalValue = isUndead ? 4.0f * (effect.getAmplifier() + 1) : 6.0f * (effect.getAmplifier() + 1); isHealing = isUndead; }
                    }
                }
            }
        } else if (stack.isOf(Items.TRIDENT)) {
            float phys = calculateRawPhysical(client, livingTarget, stack, name, isCrit);
            phys = isUsingThisHand ? 8.0f : phys;
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, isUsingThisHand, isExplosion);
        } else if (isSpear && isUsingThisHand) {
            Vec3d vel = client.player.getVelocity();
            if (client.player.getVehicle() != null) vel = client.player.getVehicle().getVelocity();
            Vec3d relativeVel = vel.subtract(livingTarget.getVelocity());
            double speedBps = relativeVel.length() * 20.0;
            float base = name.contains("netherite") ? 8.0f : 5.0f;
            float phys = (float) (base + (speedBps * 0.8f));
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, false, false);
        } else {
            float phys = calculateRawPhysical(client, livingTarget, stack, name, isCrit);
            float magicCap = calculateMagicCap(client.player, stack);
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, magicCap, isActuallyProjectile, isExplosion);
        }

        // Dragon Perching & Body Logic
        if (livingTarget instanceof EnderDragonEntity dragon) {
            // FIX: Ensure finalValue > 0 so that immune Potions/Regen don't accidentally get buffed to 1.0 by the body math
            if (!isHealing && overrideText == null && finalValue > 0) {
                if (!isDragonHead) {
                    finalValue = (finalValue / 4.0f) + 1.0f;
                }
            }
            int phaseId = dragon.getDataTracker().get(EnderDragonEntity.PHASE_TYPE);
            // FIX: Projectiles don't work against perching dragons, but explosions do!
            if (phaseId >= 4 && phaseId <= 8 && isActuallyProjectile) {
                finalValue = 0; overrideText = null;
            }
        }

        // I-FRAME FIX: Exact "nd - od" implementation
        if (livingTarget.timeUntilRegen > 10 && !isHealing && overrideText == null) {
            float od = getLastDamageTaken(livingTarget);
            if (finalValue <= od) {
                finalValue = 0.0f;
            } else {
                finalValue = finalValue - od;
            }
        }

        float effectiveHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
        if (isSplash && overrideText == null) {
            finalValue = isHealing ? Math.min(finalValue, livingTarget.getMaxHealth() - livingTarget.getHealth()) : Math.min(finalValue, effectiveHealth);
        }

        // IMMUNITIES
        if (livingTarget instanceof WolfEntity wolf && !wolf.getBodyArmor().isEmpty() && !isSplash && !isLingering) finalValue = 0;
        if (livingTarget instanceof EndermanEntity && isActuallyProjectile) { finalValue = 0; overrideText = null; }
        if (livingTarget instanceof WitherEntity wither && wither.getHealth() <= wither.getMaxHealth() / 2.0f && isActuallyProjectile) { finalValue = 0; overrideText = null; }

        if (overrideText == null) finalColor = getColor(livingTarget, finalValue, stack, isHealing);

        String suffix = "";
        if (isCrit && !isActuallyProjectile && !isSpear) suffix = "^";
        else if (isUsingThisHand && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.TRIDENT) || isSpear)) suffix = "*";
        else if (isChargedCrossbow) suffix = "*";

        // BYPASS SMOOTHING FOR POTIONS AND CRITS
        boolean noSmoothing = isSplash || isLingering || isCrit;

        if (isMain) {
            mainTarget = finalValue;
            if (noSmoothing) {
                mainDisplayed = mainTarget;
            } else {
                if (Math.abs(mainDisplayed - mainTarget) < 0.05F) mainDisplayed = mainTarget;
                else mainDisplayed = MathHelper.lerp(0.15F, mainDisplayed, mainTarget);
            }

            if (mainDisplayed >= 0.1F || overrideText != null) {
                if (overrideText != null) renderString(ctx, client, overrideText, finalColor, true);
                else renderIndicator(ctx, client, mainDisplayed, finalColor, true, suffix, isHealing);
            } else {
                mainDisplayed = 0.0f;
            }
        } else {
            if (finalValue > 0 || overrideText != null || offDisplayed >= 0.1F) {
                if (noSmoothing) {
                    offDisplayed = finalValue;
                } else {
                    if (Math.abs(offDisplayed - finalValue) < 0.05F) offDisplayed = finalValue;
                    else offDisplayed = MathHelper.lerp(0.20F, offDisplayed, finalValue);
                }

                if (offDisplayed >= 0.1F || overrideText != null) {
                    if (overrideText != null) renderString(ctx, client, overrideText, finalColor, false);
                    else renderIndicator(ctx, client, offDisplayed, finalColor, false, suffix, isHealing);
                } else {
                    offDisplayed = 0.0f;
                }
            }
        }
    }

    private static float calculateRawPhysical(MinecraftClient c, LivingEntity target, ItemStack s, String n, boolean isCrit) {
        PlayerEntity p = c.player;
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
                    // Max possible explosion damage, ignoring distance
                    if (fw != null && !fw.explosions().isEmpty()) {
                        return 5.0F + (fw.explosions().size() * 2.0F);
                    }
                    return 0.0F;
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
            if (p.hasStatusEffect(StatusEffects.SLOW_FALLING)) fallDist = 0;
            if (fallDist > 1.5F) {
                float smashBonus = (fallDist <= 3.0F) ? fallDist * 4.0F : (fallDist <= 8.0F ? 12.0F + ((fallDist - 3.0F) * 2.0F) : 22.0F + ((fallDist - 8.0F) * 1.0F));
                var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                smashBonus += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.DENSITY), s) * 0.5F * fallDist;
                total += smashBonus;
                isCrit = true;
            }
        }

        if (isCrit) total *= 1.5F;
        return total;
    }

    private static float applyFinalReductions(MinecraftClient client, LivingEntity t, ItemStack s, float phys, float magicCap, boolean isProj, boolean isExplosion) {
        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        float extra = 0;
        int sharp = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SHARPNESS), s);
        if (sharp > 0) extra += 0.5F * sharp + 0.5F;
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) extra += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SMITE), s) * 2.5F;
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) extra += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BANE_OF_ARTHROPODS), s) * 2.5F;
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_IMPALING)) extra += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.IMPALING), s) * 2.5F;

        float baseDamage = phys + extra;
        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), s);
        if (breach > 0) {
            float breachFactor = Math.max(0.0F, 1.0F - (breach * 0.15F));
            armor *= breachFactor;
            toughness *= breachFactor;
        }

        float afterArmor = DamageUtil.getDamageLeft(t, baseDamage, t.getDamageSources().generic(), armor, toughness);
        float totalDamage = afterArmor + (magicCap > 0 ? Math.max(0, magicCap - afterArmor) : 0);

        int epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack piece = t.getEquippedStack(slot);
            if (!piece.isEmpty()) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), piece);
                if (isProj) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), piece) * 2;
                if (isExplosion) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), piece) * 2;
            }
        }
        return totalDamage * (1.0F - (Math.min(20.0F, (float)epf) * 0.04F));
    }

    private static float getReach(MinecraftClient c, ItemStack s, String n) {
        if (c.player == null) return 3.5F;
        float baseReach = (float) c.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);
        boolean using = c.player.isUsingItem() && c.player.getActiveItem() == s;

        if (s.isOf(Items.MACE) && c.player.fallDistance > 0.0f) return 100.0F;
        if (n.contains("spear") && using) return 100.0F;

        // Dynamic Bow/Crossbow Reach
        if (s.isOf(Items.CROSSBOW)) {
            boolean isCharged = s.contains(DataComponentTypes.CHARGED_PROJECTILES) && !s.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty();
            return isCharged ? 45.0F : baseReach;
        }
        if (s.isOf(Items.BOW)) {
            return using ? 45.0F : baseReach;
        }
        if (s.isOf(Items.TRIDENT)) {
            return using ? 45.0F : baseReach;
        }

        return n.contains("spear") ? baseReach + 1.5F : baseReach;
    }

    private static Entity getTarget(MinecraftClient client, double maxDist) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.world == null) return null;

        if (maxDist <= 10.0 && client.crosshairTarget instanceof EntityHitResult ehr) {
            Entity hit = ehr.getEntity();
            if (hit instanceof EnderDragonPart part) return part.owner;
            return hit;
        }

        HitResult blockHit = camera.raycast(maxDist, 0, false);
        double blockDist = blockHit.getType() != HitResult.Type.MISS ? blockHit.getPos().distanceTo(camera.getEyePos()) : maxDist;

        Vec3d start = camera.getEyePos();
        Vec3d rot = camera.getRotationVec(1.0F);
        Vec3d end = start.add(rot.multiply(maxDist));

        // FIX: The final parameter MUST be the squared distance (maxDist * maxDist) for proper extended reach.
        EntityHitResult entityHit = ProjectileUtil.raycast(camera, start, end, camera.getBoundingBox().stretch(rot.multiply(maxDist)).expand(1.0D), (ent) -> !ent.isSpectator() && ent.canHit(), maxDist * maxDist);

        if (entityHit != null && entityHit.getPos().distanceTo(start) < blockDist) {
            Entity result = entityHit.getEntity();
            if (result instanceof EnderDragonEntity dragon) {
                for (EnderDragonPart part : dragon.getBodyParts()) {
                    if (part.getBoundingBox().raycast(start, end).isPresent()) return part;
                }
            }
            return result;
        }
        return null;
    }

    private static void handleMainSmoothing(float actual) {
        mainTarget = actual;
    }

    private static float applyPowerEnch(MinecraftClient c, ItemStack s, float base) {
        var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int lvl = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.POWER), s);
        return lvl > 0 ? base * (1.25F + (0.25F * lvl)) : base;
    }

    private static boolean hasExplosiveFirework(ItemStack stack) {
        ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null && !charged.isEmpty()) {
            ItemStack proj = charged.getProjectiles().get(0);
            if (proj.isOf(Items.FIREWORK_ROCKET)) {
                FireworksComponent fw = proj.get(DataComponentTypes.FIREWORKS);
                return fw != null && !fw.explosions().isEmpty();
            }
        }
        return false;
    }

    private static float calculateMagicCap(PlayerEntity p, ItemStack s) {
        ItemStack proj = ItemStack.EMPTY;
        if (s.isOf(Items.BOW)) {
            for (int i = 0; i < p.getInventory().size(); i++) { if (p.getInventory().getStack(i).getItem() instanceof ArrowItem) { proj = p.getInventory().getStack(i); break; } }
        } else if (s.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent c = s.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (c != null && !c.isEmpty()) proj = c.getProjectiles().get(0);
        }
        if (proj.isEmpty() || !proj.contains(DataComponentTypes.POTION_CONTENTS)) return 0;
        PotionContentsComponent contents = proj.get(DataComponentTypes.POTION_CONTENTS);
        for (StatusEffectInstance effect : contents.getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) || effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) return 4.0F * (effect.getAmplifier() + 1);
        }
        return 0;
    }

    private static int getColor(LivingEntity target, float damage, ItemStack stack, boolean isHealing) {
        float effectiveHealth = target.getHealth() + target.getAbsorptionAmount();
        if (stack.isOf(Items.SPLASH_POTION)) {
            if (!isHealing && isEnemyHoldingTotem(target) && damage >= effectiveHealth) return 0xFFF2FF00;
            return isHealing ? 0xFF00FF00 : 0xFF990000;
        }
        if (damage >= effectiveHealth) return isEnemyHoldingTotem(target) ? 0xFFF2FF00 : 0xFFFF0000;
        return 0xFFFFFFFF;
    }

    private static boolean isEnemyHoldingTotem(LivingEntity e) {
        return e.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING) || e.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, String suffix, boolean isHealing) {
        if (!suffix.equals("X") && dmg < 0.1F) return;
        String txt = suffix.equals("X") ? "X" : (isHealing ? "+" : "") + String.format("%.1f", dmg) + suffix;
        renderString(ctx, c, txt, color, isMain);
    }

    private static void renderString(DrawContext ctx, MinecraftClient c, String txt, int color, boolean isMain) {
        int centerX = c.getWindow().getScaledWidth() / 2;
        int centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 15 : centerX - 15 - c.textRenderer.getWidth(txt);
        ctx.drawTextWithShadow(c.textRenderer, txt, x, centerY, color);
    }

    private static float getLastDamageTaken(LivingEntity entity) {
        if (!fieldSearched) {
            String[] mappings = {"lastDamageTaken", "field_6235", "lastDamage"};
            for (String mapping : mappings) {
                try {
                    lastDamageField = LivingEntity.class.getDeclaredField(mapping);
                    lastDamageField.setAccessible(true);
                    break;
                } catch (Exception ignored) {}
            }
            fieldSearched = true;
        }
        try {
            return lastDamageField != null ? lastDamageField.getFloat(entity) : 0.0F;
        } catch (Exception e) {
            return 0.0F;
        }
    }
}