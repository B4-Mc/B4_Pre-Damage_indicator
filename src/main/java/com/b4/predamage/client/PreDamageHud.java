package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.DamageUtil;

public class PreDamageHud {

    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null) return;

        if (!isMain && stack.isEmpty()) {
            offDisplayed = 0.0f;
            return;
        }

        String name = stack.getItem().toString().toLowerCase();
        boolean isUsingThisHand = client.player.isUsingItem() && client.player.getActiveItem() == stack;

        boolean isRightClickWeapon = stack.isOf(Items.BOW) ||
                stack.isOf(Items.CROSSBOW) ||
                stack.isOf(Items.TRIDENT) ||
                name.contains("spear");

        float reach = getReach(client, stack, name);
        Entity target = getTarget(client, reach);

        float finalValue = 0;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;
        float dragonMultiplier = 1.0f;
        boolean isProjectile = false;

        if (target instanceof net.minecraft.entity.boss.dragon.EnderDragonPart part) {
            if (part.name != null && part.name.contains("head")) {
                dragonMultiplier = 4.0f;
            }
            target = part.owner;
        }

        if (target instanceof LivingEntity livingTarget) {
            if (stack.isOf(Items.SPLASH_POTION)) {
                isProjectile = true;
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
                    if (livingTarget instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity) finalValue = 0;
                }
            } else if (stack.isOf(Items.TRIDENT)) {
                isProjectile = true;
                var world = client.world;
                if (world == null) return;
                var reg = world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

                int imp = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.IMPALING), stack);
                int rip = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.RIPTIDE), stack);
                float bonus = livingTarget.getType().isIn(EntityTypeTags.AQUATIC) ? imp * 2.5f : 0;

                if (isUsingThisHand) {
                    float base = (rip > 0 ? 9.0f : 8.0f) + bonus;
                    finalValue = applyFinalReductions(livingTarget, stack, base, 0, rip <= 0);
                } else {
                    finalValue = isMain ? applyFinalReductions(livingTarget, stack, 9.0f + bonus, 0, false) : 0.0f;
                }
            } else if (name.contains("spear")) {
                if (isUsingThisHand) {
                    isProjectile = true;
                    double vel = client.player.getVelocity().length() * 20.0;
                    float raw = Math.max(5.0f, (float) (vel * 1.25f));
                    finalValue = applyFinalReductions(livingTarget, stack, raw, 0, false);
                } else {
                    finalValue = isMain ? applyFinalReductions(livingTarget, stack, calculateRawPhysical(client, client.player, stack, name), 0, false) : 0.0f;
                }
            } else if (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) {
                isProjectile = true;
                if (isUsingThisHand || (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack))) {
                    float phys = calculateRawPhysical(client, client.player, stack, name);
                    float mag = calculateMagicBonus(client.player, stack, livingTarget);
                    finalValue = applyFinalReductions(livingTarget, stack, phys, mag, true);
                } else {
                    finalValue = isMain ? applyFinalReductions(livingTarget, stack, calculateRawPhysical(client, client.player, stack, name), 0, true) : 0.0f;
                }
            } else {
                if (isMain) {
                    float phys = calculateRawPhysical(client, client.player, stack, name);
                    float mag = calculateMagicBonus(client.player, stack, livingTarget);
                    finalValue = applyFinalReductions(livingTarget, stack, phys, mag, false);
                } else {
                    if (!isRightClickWeapon) return;
                    finalValue = 0.0f;
                }
            }

            if (livingTarget instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
                if (dragonMultiplier == 4.0f) {
                    finalValue = finalValue;
                } else {
                    finalValue = (finalValue * 0.25f) + 1.0f;
                }

                int phaseId = dragon.getDataTracker().get(net.minecraft.entity.boss.dragon.EnderDragonEntity.PHASE_TYPE);
                boolean isPerched = (phaseId >= 4 && phaseId <= 7);
                boolean isSpear = name.contains("spear");

                if (isPerched && isProjectile && !isSpear) {
                    finalValue = 0;
                }
            }

            if (stack.isOf(Items.SPLASH_POTION)) {
                if (!isHealing && isEnemyHoldingTotem(livingTarget) && finalValue >= livingTarget.getHealth()) {
                    finalColor = 0xFFF2FF00; // Totem Yellow
                } else {
                    finalColor = isHealing ? 0xFF00FF00 : 0xFF990000;
                }
            } else {
                finalColor = getColor(livingTarget, finalValue);
            }

            boolean useAsterisk = (stack.isOf(Items.BOW) && isUsingThisHand) || (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack));
            if (isMain) {
                handleMainSmoothing(client, name, finalValue);
                if (finalValue > 0 || stack.isEmpty()) {
                    renderIndicator(ctx, client, mainDisplayed, finalColor, true, false, useAsterisk, isHealing);
                }
            } else {
                if (finalValue > 0) {
                    offDisplayed = MathHelper.lerp(0.20F, offDisplayed, finalValue);
                    renderIndicator(ctx, client, offDisplayed, finalColor, false, false, useAsterisk, isHealing);
                } else if (isRightClickWeapon) {
                    renderIndicator(ctx, client, 0.0F, 0xFFFF0000, false, true, false, false);
                }
            }
        } else {
            if (isMain) mainDisplayed = 0.0f;
            else offDisplayed = 0.0f;
        }
    }

    private static boolean isEnemyHoldingTotem(LivingEntity e) {
        return e.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING) ||
                e.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static float calculateRawPhysical(MinecraftClient c, PlayerEntity p, ItemStack s, String n) {
        if (s.isOf(Items.BOW)) {
            if (!p.isUsingItem() || p.getActiveItem() != s) return 0.0F;
            int ticks = s.getMaxUseTime(p) - p.getItemUseTimeLeft();
            float pull = Math.min((float) ticks / 20.0F, 1.0F);
            pull = (pull * pull + pull * 2.0F) / 3.0F;
            return applyEnch(s, (pull * 7.0F) + 2.0F, "power", true);
        }

        if (s.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent charged = s.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged != null && !charged.isEmpty()) {
                ItemStack proj = charged.getProjectiles().get(0);
                if (proj.isOf(Items.FIREWORK_ROCKET)) {
                    FireworksComponent fw = proj.get(DataComponentTypes.FIREWORKS);
                    return fw != null ? 5.0F + (fw.explosions().size() * 2.0F) : 0.0F;
                }
                return applyEnch(s, 9.0F, "power", true);
            }
            return 0.0F;
        }

        double baseAttr = p.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE);
        AttributeModifiersComponent mods = s.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (var entry : mods.modifiers()) {
                if (entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) {
                    baseAttr += entry.modifier().value();
                }
            }
        }

        float enchantExtra = 0;
        ItemEnchantmentsComponent enchants = s.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants != null) {
            for (var entry : enchants.getEnchantmentEntries()) {
                var enchEntry = entry.getKey();
                int lvl = entry.getIntValue();

                if (enchEntry.matchesKey(Enchantments.SHARPNESS)) {
                    enchantExtra += (0.5F * lvl + 0.5F);
                }

                if (enchEntry.matchesKey(Enchantments.DENSITY) && s.isOf(Items.MACE)) {
                    if (p.fallDistance > 1.5F) {
                        enchantExtra += (lvl * 0.5F * p.fallDistance);
                    }
                }
            }
        }

        float strengthBonus = 0;
        if (p.hasStatusEffect(StatusEffects.STRENGTH)) {
            strengthBonus = (3.0F * (p.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1));
        }

        float weaknessPenalty = 0;
        if (p.hasStatusEffect(StatusEffects.WEAKNESS) && !s.isOf(Items.BOW) && !s.isOf(Items.CROSSBOW)) {
            weaknessPenalty = -4.0F;
        }

        float total = (float) baseAttr + enchantExtra + strengthBonus + weaknessPenalty;

        if (s.isOf(Items.MACE) && p.fallDistance > 1.5F) {
            total += (p.fallDistance * 3.0F);
        } else if (p.fallDistance > 0.5F && !p.isOnGround() && p.getVelocity().y < -0.1 && !p.isClimbing()) {
            total *= 1.5F;
        }

        return total;
    }

    private static float calculateMagicBonus(net.minecraft.entity.player.PlayerEntity p, ItemStack s, LivingEntity t) {
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
            if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE)) {
                magic += isUndead ? 0 : 6.0F * (effect.getAmplifier() + 1);
            } else if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) {
                magic += isUndead ? 6.0F * (effect.getAmplifier() + 1) : 0;
            }
        }
        return magic;
    }

    private static float applyFinalReductions(LivingEntity t, ItemStack s, float phys, float magic, boolean isProjecting) {
        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        var reg = t.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), s);
        if (breach > 0) {
            armor *= (1.0F - (breach * 0.15F));
        }

        float baseDamage = phys;
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
            baseDamage += (EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SMITE), s) * 2.5F);
        }
        if (t.getType().isIn(EntityTypeTags.ARTHROPOD)) {
            baseDamage += (EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BANE_OF_ARTHROPODS), s) * 2.5F);
        }

        float damageAfterArmor = DamageUtil.getDamageLeft(t, baseDamage, t.getDamageSources().generic(), armor, toughness);

        int epf = 0;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack piece = t.getEquippedStack(slot);
            if (!piece.isEmpty()) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), piece);

                if (isProjecting) {
                    epf += (EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), piece) * 2);
                }
            }
        }

        float reductionFactor = Math.min(20.0F, (float)epf);
        float finalPhys = damageAfterArmor * (1.0F - (reductionFactor * 0.04F));

        return finalPhys + magic;
    }

    private static float applyEnch(ItemStack s, float b, String n, boolean isBow) {
        ItemEnchantmentsComponent e = s.get(DataComponentTypes.ENCHANTMENTS);
        if (e != null) {
            for (var entry : e.getEnchantmentEntries()) {
                if (entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("").contains(n)) {
                    int lvl = entry.getIntValue();
                    if (isBow && n.equals("power")) return b * (1.25F + (0.25F * lvl));
                    return b + (lvl + 1) * 0.5F + 0.5F;
                }
            }
        }
        return b;
    }

    private static void handleMainSmoothing(MinecraftClient client, String name, float actual) {
        mainTarget = actual;
        if (Math.abs(mainDisplayed - mainTarget) < 0.05F) mainDisplayed = mainTarget;
        else mainDisplayed = MathHelper.lerp(0.15F, mainDisplayed, mainTarget);
    }

    private static float getReach(MinecraftClient c, ItemStack s, String n) {
        if (c.player == null) return 3.5F;
        boolean isUsingThisItem = c.player.isUsingItem() && c.player.getActiveItem() == s;
        if (s.isOf(Items.MACE) && c.player.fallDistance > 0.0f) return 100.0F;
        if (n.contains("spear") && isUsingThisItem) return 100.0F;
        if (s.isOf(Items.BOW) && isUsingThisItem) return 30.0F;
        if (s.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(s)) return 30.0F;
        if (s.isOf(Items.TRIDENT) && isUsingThisItem) return 30.0F;
        return n.contains("spear") ? 4.5F : 3.5F;
    }

    private static int getColor(LivingEntity target, float damage) {
        float health = target.getHealth();
        if (damage >= health) {
            if (isEnemyHoldingTotem(target)) return 0xFFF2FF00;
            return 0xFFFF0000;
        }
        return 0xFFFFFFFF;
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

    private static void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, boolean showX, boolean useAsterisk, boolean isHealing) {
        if (!showX && dmg < 0.1F) return;
        String txt = showX ? "x" : (isHealing ? "+" : "") + String.format("%.1f", dmg) + (useAsterisk ? "*" : "");
        int centerX = c.getWindow().getScaledWidth() / 2;
        int centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 15 : centerX - 15 - c.textRenderer.getWidth(txt);
        ctx.drawTextWithShadow(c.textRenderer, txt, x, centerY, color);
    }
}