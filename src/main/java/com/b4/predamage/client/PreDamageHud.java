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
import net.minecraft.util.hit.EntityHitResult;
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

        // FIX: Empty mainhand should process (fist damage), offhand should not
        if (stack.isEmpty() && !isMain) {
            offDisplayed = 0.0f;
            return;
        }

        String name = stack.getItem().toString().toLowerCase();
        boolean isSpear = name.contains("spear");
        boolean isUsingThisHand = client.player.isUsingItem() && client.player.getActiveItem() == stack;

        // FIX: Strict offhand filtering (ignores shields, food, spyglasses)
        if (!isMain) {
            boolean isWeapon = stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.TRIDENT) ||
                    isSpear || name.contains("sword") || name.contains("axe") || name.contains("mace") ||
                    stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
            boolean isChargedCrossbow = stack.isOf(Items.CROSSBOW) && stack.contains(DataComponentTypes.CHARGED_PROJECTILES) && !stack.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty();

            if (!isWeapon && !isChargedCrossbow) {
                offDisplayed = 0.0f;
                return;
            }
        }

        float reach = getReach(client, stack, name);
        EntityHitResult hitResult = getTargetHit(client, reach);
        Entity target = hitResult != null ? hitResult.getEntity() : null;
        Vec3d hitPos = hitResult != null ? hitResult.getPos() : null;

        if (target instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        if (!(target instanceof LivingEntity livingTarget) || target instanceof ArmorStandEntity || target instanceof CreakingEntity) {
            if (isMain) mainDisplayed = 0.0f; else offDisplayed = 0.0f;
            return;
        }

        boolean isCrit = client.player.fallDistance > 0.0F
                && !client.player.isOnGround()
                && !client.player.isClimbing()
                && !client.player.isTouchingWater()
                && !client.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !client.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                && !client.player.hasVehicle();

        if (isSpear) isCrit = false;

        float finalValue = 0;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;
        String overrideText = null;
        float dragonMultiplier = 1.0f;

        boolean isSplash = stack.isOf(Items.SPLASH_POTION);
        boolean isLingering = stack.isOf(Items.LINGERING_POTION);
        boolean isActuallyProjectile = stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || (stack.isOf(Items.TRIDENT) && isUsingThisHand) || isSplash || isLingering;
        boolean isExplosion = stack.isOf(Items.CROSSBOW) && hasExplosiveFirework(stack);

        if (target instanceof EnderDragonPart part) {
            if (part.name != null && part.name.contains("head")) {
                dragonMultiplier = 1.0f; // Head takes normal 1x
            } else {
                dragonMultiplier = 0.25f; // Body takes 0.25x
            }
            target = part.owner;
        }

        // POISONS, REGEN, LINGERING AND SPLASH POTIONS FIX
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

                    if (isPoison && !isImmuneToPoison) {
                        overrideText = "-" + heartStr;
                        finalColor = 0xFF4E9331;
                    } else if (isRegen && !isUndead) {
                        overrideText = "+" + heartStr;
                        finalColor = 0xFF00FF00;
                    } else if (isLingering) {
                        if (isHeal) {
                            overrideText = isUndead ? "-" + heartStr : "+" + heartStr;
                            finalColor = isUndead ? 0xFF990000 : 0xFF00FF00;
                        } else if (isHarming) {
                            overrideText = isUndead ? "+" + heartStr : "-" + heartStr;
                            finalColor = isUndead ? 0xFF00FF00 : 0xFF990000;
                        }
                    } else if (isSplash) {
                        if (isHeal) {
                            finalValue = isUndead ? 6.0f * (effect.getAmplifier() + 1) : 4.0f * (effect.getAmplifier() + 1);
                            isHealing = !isUndead;
                        } else if (isHarming) {
                            finalValue = isUndead ? 4.0f * (effect.getAmplifier() + 1) : 6.0f * (effect.getAmplifier() + 1);
                            isHealing = isUndead;
                        }
                    }
                }
                if (livingTarget instanceof EnderDragonEntity) {
                    finalValue = 0;
                    overrideText = null;
                }
            }
        } else if (stack.isOf(Items.TRIDENT)) {
            float phys = calculateRawPhysical(client, client.player, stack, name, isCrit);
            phys = isUsingThisHand ? 8.0f : phys; // 8.0 thrown, melee uses standard raw phys (9.0)
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, isUsingThisHand, isExplosion, hitPos);
        } else if (isSpear && isUsingThisHand) {
            Vec3d vel = client.player.getVelocity();
            if (client.player.getVehicle() != null) vel = client.player.getVehicle().getVelocity();
            double speedBps = vel.length() * 20.0; // Restored full 3D velocity
            float base = name.contains("netherite") ? 8.0f : 5.0f;
            float phys = (float) (base + (speedBps * 0.8f));
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0, false, false, hitPos);
        } else {
            // Standard weapons (Swords, Axes, Mace, Bow, Crossbow)
            float phys = calculateRawPhysical(client, client.player, stack, name, isCrit);
            float magicCap = calculateMagicCap(client.player, stack);
            finalValue = applyFinalReductions(client, livingTarget, stack, phys, magicCap, isActuallyProjectile, isExplosion, hitPos);
        }

        // Capping splash healing based on health
        float effectiveHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
        if (isSplash && overrideText == null) {
            if (isHealing) {
                finalValue = Math.min(finalValue, livingTarget.getMaxHealth() - livingTarget.getHealth());
            } else {
                finalValue = Math.min(finalValue, effectiveHealth);
            }
        }

        if (livingTarget instanceof WolfEntity wolf && !wolf.getBodyArmor().isEmpty()) {
            if (!isSplash && !isLingering) finalValue = 0;
        }
        if (livingTarget instanceof EndermanEntity && isActuallyProjectile) {
            finalValue = 0;
            overrideText = null;
        }
        if (livingTarget instanceof WitherEntity wither && wither.getHealth() <= wither.getMaxHealth() / 2.0f) {
            if (isActuallyProjectile) {
                finalValue = 0;
                overrideText = null;
            }
        }
        if (livingTarget instanceof EnderDragonEntity) {
            if (dragonMultiplier == 0.25f) finalValue = (finalValue * 0.25f) + 1.0f;
            int phaseId = livingTarget.getDataTracker().get(EnderDragonEntity.PHASE_TYPE);
            if ((phaseId >= 4 && phaseId <= 7) && isActuallyProjectile) {
                finalValue = 0;
                overrideText = null;
            }
        }

        if (overrideText == null) finalColor = getColor(livingTarget, finalValue, stack, isHealing);

        String suffix = "";
        if (isCrit && !isActuallyProjectile && !isSpear) {
            suffix = "^";
        } else if ((stack.isOf(Items.BOW) && isUsingThisHand) || (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack))) {
            suffix = "*";
        }

        if (isMain) {
            handleMainSmoothing(finalValue);
            if (overrideText != null) {
                renderString(ctx, client, overrideText, finalColor, true);
            } else {
                renderIndicator(ctx, client, mainDisplayed, finalColor, true, suffix, isHealing);
            }
        } else if (finalValue > 0 || overrideText != null) {
            if (overrideText != null) {
                renderString(ctx, client, overrideText, finalColor, false);
            } else {
                offDisplayed = MathHelper.lerp(0.20F, offDisplayed, finalValue);
                renderIndicator(ctx, client, offDisplayed, finalColor, false, suffix, isHealing);
            }
        }
    }

    private static float applyFinalReductions(MinecraftClient client, LivingEntity t, ItemStack s, float phys, float magicCap, boolean isProj, boolean isExplosion, Vec3d hitPos) {
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

        // FIX: Breach reduces both armor and toughness by 15% per level effectively
        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), s);
        if (breach > 0) {
            float breachFactor = Math.max(0.0F, 1.0F - (breach * 0.15F));
            armor *= breachFactor;
            toughness *= breachFactor;
        }

        float afterArmor = DamageUtil.getDamageLeft(t, baseDamage, t.getDamageSources().generic(), armor, toughness);

        // FIX: Firework falloff based on proximity to target's feet
        if (isExplosion && hitPos != null) {
            double dist = hitPos.distanceTo(new Vec3d(t.getX(), t.getY(), t.getZ()));
            afterArmor *= (float) Math.max(0.0, 1.0 - (dist / 4.0));
        }

        // FIX: Harming arrow cap logic (bypasses armor, replaces arrow damage if higher)
        float magic = 0;
        if (magicCap > 0) {
            magic = Math.max(0, magicCap - afterArmor);
        }

        float totalDamage = afterArmor + magic;

        // FIX: Fallback to old reliable EquipmentSlot checking for multi-version mapping stability
        int epf = 0;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack piece = t.getEquippedStack(slot);
            if (!piece.isEmpty()) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), piece);
                if (isProj) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), piece) * 2;
                if (isExplosion) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), piece) * 2;
            }
        }
        float reductionFactor = Math.min(20.0F, (float)epf);
        totalDamage *= (1.0F - (reductionFactor * 0.04F));

        return totalDamage;
    }

    private static float calculateRawPhysical(MinecraftClient c, PlayerEntity p, ItemStack s, String n, boolean isCrit) {
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
                    if (fw != null && !fw.explosions().isEmpty()) return 5.0F + (fw.explosions().size() * 2.0F);
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

        // FIX: Crit 1.5x modifier correctly applied *before* enchantments
        if (isCrit) total *= 1.5F;

        // FIX: 1.21 Mace Scale Logic
        if (s.isOf(Items.MACE)) {
            float fallDist = (float) (maceFallStartY != -1.0 ? Math.max(0, maceFallStartY - p.getY()) : 0);
            if (p.hasStatusEffect(StatusEffects.SLOW_FALLING)) fallDist = 0;

            if (fallDist > 1.5F) {
                float smashBonus = 0;
                if (fallDist <= 3.0F) smashBonus = fallDist * 3.0F;
                else if (fallDist <= 8.0F) smashBonus = 9.0F + ((fallDist - 3.0F) * 1.5F);
                else smashBonus = 16.5F + ((fallDist - 8.0F) * 0.5F);

                var reg = c.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                int density = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.DENSITY), s);
                smashBonus += density * 0.5F * fallDist;

                total += smashBonus;
            }
        }
        return total;
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
        for (StatusEffectInstance effect : contents.getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) || effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) {
                return 4.0F * (effect.getAmplifier() + 1); // 4 for level 1, 8 for level 2
            }
        }
        return 0;
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
        if (s.contains(DataComponentTypes.CHARGED_PROJECTILES) && !s.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty()) return 30.0F;
        if ((s.isOf(Items.BOW) || s.isOf(Items.TRIDENT)) && using) return 30.0F;
        return n.contains("spear") ? 4.5F : 3.5F;
    }

    private static EntityHitResult getTargetHit(MinecraftClient c, float r) {
        Entity cam = c.getCameraEntity();
        if (cam == null || c.world == null) return null;
        Vec3d start = cam.getCameraPosVec(1.0F);
        Vec3d rot = cam.getRotationVec(1.0F);
        Vec3d end = start.add(rot.multiply(r));
        var box = cam.getBoundingBox().stretch(rot.multiply(r)).expand(1.0D);
        return ProjectileUtil.raycast(cam, start, end, box, (ent) -> !ent.isSpectator() && ent.canHit(), r * r);
    }

    private static int getColor(LivingEntity target, float damage, ItemStack stack, boolean isHealing) {
        float effectiveHealth = target.getHealth() + target.getAbsorptionAmount();
        if (stack.isOf(Items.SPLASH_POTION)) {
            if (!isHealing && isEnemyHoldingTotem(target) && damage >= effectiveHealth) return 0xFFF2FF00;
            return isHealing ? 0xFF00FF00 : 0xFF990000;
        }
        if (damage >= effectiveHealth) {
            return isEnemyHoldingTotem(target) ? 0xFFF2FF00 : 0xFFFF0000;
        }
        return 0xFFFFFFFF;
    }

    private static boolean isEnemyHoldingTotem(LivingEntity e) {
        return e.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING) || e.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, String suffix, boolean isHealing) {
        if (dmg < 0.1F) return;
        String txt = (isHealing ? "+" : "") + String.format("%.1f", dmg) + suffix;
        renderString(ctx, c, txt, color, isMain);
    }

    private static void renderString(DrawContext ctx, MinecraftClient c, String txt, int color, boolean isMain) {
        int centerX = c.getWindow().getScaledWidth() / 2;
        int centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 15 : centerX - 15 - c.textRenderer.getWidth(txt);
        ctx.drawTextWithShadow(c.textRenderer, txt, x, centerY, color);
    }
}