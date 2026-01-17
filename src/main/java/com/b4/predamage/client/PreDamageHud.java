package com.b4.predamage.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.*;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.*;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.Hand;

public class PreDamageHud {

    private float mainDisplayed = 0.0F;
    private float mainTarget = 0.0F;
    private int maceUpdateTicks = 0;
    private float offDisplayed = 0.0F;

    public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        ItemStack mainStack = client.player.getMainHandStack();
        processHand(client, drawContext, mainStack, true);

        ItemStack offStack = client.player.getOffHandStack();
        if (isOffhandWeapon(offStack)) {
            processHand(client, drawContext, offStack, false);
        } else {
            offDisplayed = MathHelper.lerp(0.1F, offDisplayed, 0.0F);
        }
    }

    private void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        String name = stack.getItem().toString().toLowerCase();
        float reach = getReach(client, stack, name);
        Entity target = getTarget(client, reach);

        if (target instanceof LivingEntity livingTarget) {
            float physicalDmg = calculateRawPhysical(client, client.player, stack, name);
            float magicDmg = calculateMagicBonus(client.player, stack, livingTarget);
            float finalDamage = applyFinalReductions(livingTarget, stack, physicalDmg, magicDmg);

            boolean isCharging = client.player.isUsingItem() && client.player.getActiveItem() == stack;
            boolean isCrossbowCharged = stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
            boolean useAsterisk = isCrossbowCharged || ((stack.isOf(Items.BOW) || stack.isOf(Items.TRIDENT)) && isCharging);

            if (isMain) {
                if (name.contains("spear")) {
                    mainDisplayed = finalDamage;
                    mainTarget = finalDamage;
                } else {
                    handleMainSmoothing(client, name, finalDamage);
                }
                renderIndicator(ctx, client, mainDisplayed, getColor(livingTarget, finalDamage), true, false, useAsterisk);
            } else {
                if (isCharging || isCrossbowCharged) {
                    if (name.contains("spear")) offDisplayed = finalDamage;
                    else offDisplayed = MathHelper.lerp(0.20F, offDisplayed, finalDamage);
                    renderIndicator(ctx, client, offDisplayed, getColor(livingTarget, finalDamage), false, false, useAsterisk);
                } else {
                    renderIndicator(ctx, client, 0, getColor(livingTarget, finalDamage), false, true, false);
                    offDisplayed = 0;
                }
            }
        } else {
            if (isMain) {
                mainDisplayed = MathHelper.lerp(0.1F, mainDisplayed, 0.0F);
                if (mainDisplayed < 0.1F) { mainDisplayed = 0.0F; mainTarget = 0.0F; }
            } else {
                offDisplayed = MathHelper.lerp(0.1F, offDisplayed, 0.0F);
            }
        }
    }

    private float calculateRawPhysical(MinecraftClient c, net.minecraft.entity.player.PlayerEntity p, ItemStack s, String n) {
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

        float enchantExtra = 0;
        ItemEnchantmentsComponent enchants = s.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants != null) {
            for (var entry : enchants.getEnchantmentEntries()) {
                String path = entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("");
                if (path.contains("sharpness")) enchantExtra += (0.5F * entry.getIntValue() + 0.5F);
            }
        }

        float strengthBonus = 0;
        if (p.hasStatusEffect(StatusEffects.STRENGTH)) {
            strengthBonus = (3.0F * (p.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1));
        }

        if (n.contains("spear")) {
            float base = n.contains("netherite") ? 5.0F : n.contains("diamond") ? 4.0F : n.contains("iron") ? 3.0F : 2.0F;
            float total = base + enchantExtra + strengthBonus;

            if (p.isUsingItem() && p.getActiveItem() == s) {
                double velocity;
                if (p.getVehicle() != null) {
                    velocity = p.getVehicle().getVelocity().length();
                } else {
                    velocity = p.getVelocity().length();
                }

                total += (float) (velocity * 20.0);
            } else if (p.fallDistance > 0.5F && !p.isOnGround() && p.getVelocity().y < -0.1) {
                total *= 1.5F;
            }
            return total;
        }
        double baseAttr = 1.0;
        AttributeModifiersComponent mods = s.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods != null) {
            for (var entry : mods.modifiers()) {
                if (entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) baseAttr += entry.modifier().value();
            }
        }

        float total = (float)baseAttr + enchantExtra + strengthBonus;

        if (n.contains("mace") && p.fallDistance > 1.5F) {
            total += (p.fallDistance * 3.0F);
        } else if (p.fallDistance > 0.5F && !p.isOnGround() && p.getVelocity().y < -0.1) {
            boolean isThrowingTrident = s.isOf(Items.TRIDENT) && p.isUsingItem() && p.getActiveItem() == s;
            if (!isThrowingTrident) {
                total *= 1.5F;
            }
        }

        return total;
    }

    private float calculateMagicBonus(net.minecraft.entity.player.PlayerEntity p, ItemStack s, LivingEntity t) {
        if (s.isOf(Items.BOW) && (!p.isUsingItem() || p.getActiveItem() != s)) return 0;

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

    private float applyFinalReductions(LivingEntity t, ItemStack s, float phys, float magic) {
        float armor = (float) t.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) t.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        ItemEnchantmentsComponent e = s.get(DataComponentTypes.ENCHANTMENTS);
        if (e != null) {
            for (var entry : e.getEnchantmentEntries()) {
                if (entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("").contains("breach")) {
                    armor *= (1.0F - (entry.getIntValue() * 0.15F));
                }
            }
        }
        float damageAfterArmor = DamageUtil.getDamageLeft(t, phys, t.getDamageSources().generic(), armor, toughness);
        float totalPreProtection = damageAfterArmor + magic;
        int epf = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.isArmorSlot()) {
                ItemEnchantmentsComponent ae = t.getEquippedStack(slot).get(DataComponentTypes.ENCHANTMENTS);
                if (ae != null) {
                    for (var entry : ae.getEnchantmentEntries()) {
                        if (entry.getKey().getKey().map(k -> k.getValue().getPath()).orElse("").contains("protection")) epf += entry.getIntValue();
                    }
                }
            }
        }
        float damageAfterProt = totalPreProtection * (1.0F - (Math.min(epf, 20) * 0.04F));
        if (t.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int amp = t.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            damageAfterProt *= Math.max(0, 1.0F - (amp * 0.2F));
        }
        return damageAfterProt;
    }

    private float applyEnch(ItemStack s, float b, String n, boolean isBow) {
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

    private void handleMainSmoothing(MinecraftClient client, String name, float actual) {
        boolean isMaceFalling = name.contains("mace") && client.player.fallDistance > 1.5F;
        float speed = isMaceFalling ? 0.33F : 0.15F;
        if (isMaceFalling) {
            maceUpdateTicks++;
            if (maceUpdateTicks >= 3) { mainTarget = actual; maceUpdateTicks = 0; }
        } else {
            mainTarget = actual;
            maceUpdateTicks = 0;
        }
        if (Math.abs(mainDisplayed - mainTarget) < 0.05F) mainDisplayed = mainTarget;
        else mainDisplayed = MathHelper.lerp(speed, mainDisplayed, mainTarget);
    }

    private boolean isOffhandWeapon(ItemStack s) {
        String n = s.getItem().toString().toLowerCase();
        return s.isOf(Items.BOW) || s.isOf(Items.CROSSBOW) || s.isOf(Items.TRIDENT) || n.contains("spear");
    }

    private float getReach(MinecraftClient c, ItemStack s, String n) {
        if (n.contains("mace") && c.player.fallDistance > 1.5F) return 100.0F;
        if (s.isOf(Items.BOW) || (s.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(s))) return 30.0F;

        if ((s.isOf(Items.TRIDENT) || n.contains("spear")) && c.player.isUsingItem()) return 30.0F;

        if (n.contains("spear")) return 4.5F;

        return 3.5F;
    }

    private int getColor(LivingEntity t, float dmg) {
        for (StatusEffectInstance effect : t.getStatusEffects()) {
            if (effect.getEffectType().value().getTranslationKey().contains("resistance")) return 0xFFFFD700;
        }
        float hp = t.getHealth() + t.getAbsorptionAmount();
        if (dmg > 0 && dmg >= hp) {
            boolean totem = t.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || t.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
            return totem ? 0xFFFFFF00 : 0xFFFF0000;
        }
        return 0xFFFFFFFF;
    }

    private Entity getTarget(MinecraftClient c, float r) {
        Entity cam = c.getCameraEntity();
        if (cam == null) return null;
        Vec3d start = cam.getCameraPosVec(1.0F);
        Vec3d end = start.add(cam.getRotationVec(1.0F).multiply(r));
        BlockHitResult b = c.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, cam));
        double d = b.getType() != HitResult.Type.MISS ? b.getPos().squaredDistanceTo(start) : r * r;
        EntityHitResult e = ProjectileUtil.raycast(cam, start, end, cam.getBoundingBox().stretch(cam.getRotationVec(1.0F).multiply(r)).expand(1.0D), (ent) -> !ent.isSpectator() && ent.canHit(), d);
        return e != null ? e.getEntity() : null;
    }

    private void renderIndicator(DrawContext ctx, MinecraftClient c, float dmg, int color, boolean isMain, boolean showX, boolean useAsterisk) {
        String baseTxt = showX ? "X" : String.format("%.1f", dmg);
        String txt = useAsterisk && !showX ? baseTxt + "*" : baseTxt;
        if (!showX && dmg <= 0.1F) return;
        int centerX = c.getWindow().getScaledWidth() / 2;
        int centerY = (c.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 12 : centerX - 12 - c.textRenderer.getWidth(txt);
        ctx.drawTextWithShadow(c.textRenderer, txt, x, centerY, color);
    }
}