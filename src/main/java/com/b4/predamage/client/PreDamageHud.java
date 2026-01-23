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
import net.minecraft.util.Hand;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.DamageUtil; // Just in case

public class PreDamageHud {

    // 1. Static Variables (Required for static methods)
    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;

    // 2. The Main Processing Logic
    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null) return;

        // Main hand can be empty (fists), off-hand ignores empty
        if (!isMain && stack.isEmpty()) {
            offDisplayed = 0.0f;
            return;
        }

        String name = stack.getItem().toString().toLowerCase();
        boolean isUsingThisHand = client.player.isUsingItem() && client.player.getActiveItem() == stack;

        // Strict Filter for Off-hand "X"
        boolean isRightClickWeapon = stack.isOf(Items.BOW) ||
                stack.isOf(Items.CROSSBOW) ||
                stack.isOf(Items.TRIDENT) ||
                name.contains("spear");

        float reach = getReach(client, stack, name);
        Entity target = getTarget(client, reach);

        float finalValue = 0;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;

        if (target instanceof LivingEntity livingTarget) {
            // --- POTION LOGIC ---
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
                    finalColor = isHealing ? 0xFF00FF00 : 0xFF990000; // Lime and Crimson
                }
            }
            // --- 2. SPEAR LOGIC (1.21.11 3D Momentum) ---
            else if (name.contains("spear")) {
                if (isUsingThisHand) {
                    // Includes vertical (Y) momentum for diving attacks
                    double velocity = client.player.getVelocity().length() * 20.0;
                    finalValue = Math.max(5.0f, (float) (velocity * 1.25f));
                } else {
                    finalValue = isMain ? calculateRawPhysical(client, client.player, stack, name) : 0.0f;
                }
                finalColor = getColor(livingTarget, finalValue);
            }
            // --- 3. PROJECTILE WEAPONS ---
            else if (stack.isOf(Items.TRIDENT) || stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) {
                if (isUsingThisHand || (stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack))) {
                    finalValue = calculateRawPhysical(client, client.player, stack, name);
                } else {
                    finalValue = isMain ? calculateRawPhysical(client, client.player, stack, name) : 0.0f;
                }
                finalColor = getColor(livingTarget, finalValue);
            }
            // --- 4. GENERAL MELEE / FISTS ---
            else {
                if (isMain) {
                    finalValue = applyFinalReductions(livingTarget, stack, calculateRawPhysical(client, client.player, stack, name), calculateMagicBonus(client.player, stack, livingTarget));
                } else {
                    if (!isRightClickWeapon) return;
                    finalValue = 0.0f;
                }
                finalColor = getColor(livingTarget, finalValue);
            }

            // --- RENDERING ---
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

    // --- STATIC HELPER METHODS ---

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
                    enchantExtra += (lvl * 0.5F * Math.max(0, p.fallDistance));
                }
            }
        }

        float strengthBonus = 0;
        if (p.hasStatusEffect(StatusEffects.STRENGTH)) {
            strengthBonus = (3.0F * (p.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1));
        }

        float total = (float) baseAttr + enchantExtra + strengthBonus;

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

    private static float applyFinalReductions(LivingEntity t, ItemStack s, float phys, float magic) {
        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);

        // 1.21.11 Mapping Fix: Use getEntityWorld() or the client instance
        var world = t.getEntityWorld();
        var registryManager = world.getRegistryManager();
        var enchantmentRegistry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);

        // 1. BREACH (Reduces armor effectiveness)
        int breachLvl = EnchantmentHelper.getLevel(enchantmentRegistry.getOrThrow(Enchantments.BREACH), s);
        if (breachLvl > 0) {
            // 1.21.11 Breach: 15% reduction per level
            armor *= (1.0F - (breachLvl * 0.15F));
        }

        // 2. MOB-SPECIFIC BONUSES
        float specificBonus = 0;

        // Smite (+2.5 damage per level)
        if (t.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
            int smiteLvl = EnchantmentHelper.getLevel(enchantmentRegistry.getOrThrow(Enchantments.SMITE), s);
            specificBonus += (smiteLvl * 2.5F);
        }

        // Bane of Arthropods (+2.5 damage per level)
        if (t.getType().isIn(EntityTypeTags.ARTHROPOD)) {
            int baneLvl = EnchantmentHelper.getLevel(enchantmentRegistry.getOrThrow(Enchantments.BANE_OF_ARTHROPODS), s);
            specificBonus += (baneLvl * 2.5F);
        }

        // Impaling (Now covers aquatic + wet mobs in 1.21.11)
        if (t.getType().isIn(EntityTypeTags.AQUATIC) || t.isTouchingWaterOrRain()) {
            int impLvl = EnchantmentHelper.getLevel(enchantmentRegistry.getOrThrow(Enchantments.IMPALING), s);
            specificBonus += (impLvl * 2.5F);
        }

        // Calculate final damage after armor reduction
        float damageAfterArmor = DamageUtil.getDamageLeft(t, phys + specificBonus, t.getDamageSources().generic(), armor, toughness);
        return damageAfterArmor + magic;
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

    private static int getColor(LivingEntity t, float dmg) {
        float hp = t.getHealth() + t.getAbsorptionAmount();
        if (dmg >= hp) return 0xFFFF0000;
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