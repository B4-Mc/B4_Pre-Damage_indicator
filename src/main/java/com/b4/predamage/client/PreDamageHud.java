package com.b4.predamage.client;

import com.b4.predamage.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreakingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AbstractNautilusEntity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.Locale;

public class PreDamageHud {
    private static final int IMMUNE_COLOR = 0xFF0000AA;

    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;
    private static double maceFallStartY = -1.0;

    private static boolean wasCritMain = false;
    private static boolean wasCritOff = false;

    private static boolean debugMenuEnabled = false;
    private static boolean debugKeyHeld = false;

    private static final long TARGET_LOSS_GRACE_MS = 2000L;
    private static long mainLastTargetTimestampMs = 0L;
    private static String mainLastWeaponKey = "";

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null || client.world == null) return;
        if (!ModConfig.modEnabled) return;

        debugMenuEnabled = ModConfig.debugOverlayEnabled;
        toggleDebugMenu(client);
        updateMaceFallTracking(client.player);

        if (isMain) {
            String weaponKey = buildMainWeaponKey(client.player, stack);
            if (!weaponKey.equals(mainLastWeaponKey)) {
                mainLastWeaponKey = weaponKey;
                resetMainSmoothingState();
            }
        }

        if (stack.isEmpty() && !isMain) {
            offDisplayed = 0.0f;
            return;
        }

        boolean isSpear = isSpearWeapon(stack);
        boolean isSnowball = stack.isOf(Items.SNOWBALL);
        boolean isUsingThisHand = client.player.isUsingItem()
                && client.player.getActiveHand() == (isMain ? Hand.MAIN_HAND : Hand.OFF_HAND);
        boolean isChargedCrossbow = stack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
        boolean potentialInteractionHealingItem = ModConfig.enableInteractionHealing && isPotentialInteractionHealingItem(stack);
        String damageType = resolveDamageType(stack, isUsingThisHand, isChargedCrossbow);

        if (!isMain) {
            boolean isThrownPotion = stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
            boolean isValidOffhandWeapon = stack.isOf(Items.BOW)
                    || stack.isOf(Items.CROSSBOW)
                    || stack.isOf(Items.TRIDENT)
                    || isSpear
                    || isSnowball
                    || isThrownPotion
                    || potentialInteractionHealingItem;
            if (!isValidOffhandWeapon) {
                offDisplayed = 0.0f;
                return;
            }

            boolean isUsingOffhand = client.player.isUsingItem() && client.player.getActiveHand() == Hand.OFF_HAND;
            if (!isUsingOffhand && !isChargedCrossbow && !isThrownPotion && !potentialInteractionHealingItem) {
                renderIndicator(ctx, client, 0.1F, applyConfiguredColor(0xFFFFFFFF), false, "X", false);
                return;
            }
        }

        ReachProfile reachProfile = getReachProfile(client, stack, isSpear, isUsingThisHand);
        Entity rawHit = getTarget(client, reachProfile);

        if (rawHit instanceof PlayerEntity playerTarget && (playerTarget.isCreative() || playerTarget.isSpectator())) {
            if (isMain) {
                expireOrKeepMainSmoothingState();
                if (debugMenuEnabled) {
                    renderDebugPanel(ctx, client, stack, rawHit, new DamageBreakdown(), false, isSpear, isUsingThisHand, false, isMain, damageType);
                }
            } else {
                offDisplayed = 0.0f;
            }
            return;
        }

        boolean isDragonHead = false;
        Entity targetEntity = rawHit;
        if (rawHit instanceof EnderDragonPart part) {
            isDragonHead = part.name != null && part.name.contains("head");
            targetEntity = part.owner;
        }

        if (!(targetEntity instanceof LivingEntity livingTarget)
                || targetEntity instanceof ArmorStandEntity
                || targetEntity instanceof CreakingEntity) {
            if (isMain) {
                expireOrKeepMainSmoothingState();
                if (debugMenuEnabled) {
                    renderDebugPanel(ctx, client, stack, rawHit, new DamageBreakdown(), false, isSpear, isUsingThisHand, false, isMain, damageType);
                }
            } else {
                offDisplayed = 0.0f;
            }
            return;
        }
        if (isMain) {
            mainLastTargetTimestampMs = System.currentTimeMillis();
        }

        DamageBreakdown breakdown = new DamageBreakdown();
        boolean isCrit = isVanillaCrit(client.player, livingTarget) && !isSpear;

        float finalValue = 0.0f;
        int finalColor = 0xFFFFFFFF;
        boolean isHealing = false;
        String overrideText = null;

        boolean isSplash = stack.isOf(Items.SPLASH_POTION);
        boolean isLingering = stack.isOf(Items.LINGERING_POTION);
        boolean isExplosion = stack.isOf(Items.CROSSBOW) && hasExplosiveFirework(stack);
        boolean isProjectile = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.SNOWBALL) || (stack.isOf(Items.TRIDENT) && isUsingThisHand))
                && !isExplosion;
        boolean blockedByShield = false;

        if (isBlockedByShield(client.player, livingTarget)) {
            var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            boolean pierces = stack.isOf(Items.CROSSBOW)
                    && EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PIERCING), stack) > 0;

            if (!pierces && !isSplash && !isLingering) {
                blockedByShield = true;
            }
        }

        float directHealing = ModConfig.enableInteractionHealing ? calculateInteractionHealing(livingTarget, stack) : 0.0f;
        if (directHealing > 0.0f && overrideText == null) {
            finalValue = directHealing;
            isHealing = true;
            breakdown.healing = true;
            breakdown.healingAmount = directHealing;
            breakdown.magicBonus = directHealing;
            breakdown.baseDamage = directHealing;
            breakdown.preMitigation = directHealing;
            breakdown.initialOutput = directHealing;
            breakdown.finalDamage = directHealing;
        }
        if (!isMain && potentialInteractionHealingItem && directHealing <= 0.0f && overrideText == null) {
            offDisplayed = 0.0f;
            renderIndicator(ctx, client, 0.1F, applyConfiguredColor(0xFFFFFFFF), false, "X", false);
            return;
        }

        if (overrideText == null) {
            if (isHealing) {
                // Direct interaction healing (e.g. iron golem repair, wolf feeding) is not mitigated.
            } else if (isSplash || isLingering) {
                PotionResult potionResult = calculatePotionResult(client, livingTarget, stack, isLingering);
                finalValue = potionResult.value;
                finalColor = potionResult.color;
                overrideText = potionResult.overrideText;
                isHealing = potionResult.healing;
                breakdown.magicBonus = potionResult.value;
                breakdown.initialOutput = potionResult.value;
            } else if (isSnowball) {
                float snowballDamage = livingTarget.getType().equals(EntityType.BLAZE) ? 3.0f : 0.0f;
                breakdown.baseDamage = snowballDamage;
                breakdown.preMitigation = snowballDamage;
                breakdown.initialOutput = snowballDamage;
                finalValue = snowballDamage;
            } else if (stack.isOf(Items.TRIDENT)) {
                float phys = calculateRawPhysical(client, livingTarget, stack, isCrit, false, false, breakdown);
                if (isUsingThisHand) {
                    phys = 8.0f;
                    breakdown.baseDamage = 8.0f;
                    breakdown.preMitigation = 8.0f;
                }
                finalValue = applyFinalReductions(client, livingTarget, stack, phys, 0.0f, isUsingThisHand, isExplosion, false, breakdown);
            } else {
                float phys = calculateRawPhysical(client, livingTarget, stack, isCrit, isSpear, isUsingThisHand, breakdown);
                float magic = calculateMagicCap(client.player, livingTarget, stack, isUsingThisHand || isChargedCrossbow);
                breakdown.magicBonus = magic;

                boolean targetIsUndead = livingTarget.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
                isHealing = (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW))
                        && isMagicHealing(stack, client.player, targetIsUndead);

                finalValue = applyFinalReductions(client, livingTarget, stack, phys, magic, isProjectile, isExplosion, isHealing, breakdown);
            }
        }

        if (livingTarget instanceof EnderDragonEntity dragon) {
            if (!isHealing && overrideText == null && finalValue > 0.0f && !isDragonHead) {
                finalValue = (finalValue / 4.0f) + 1.0f;
            }
            int phaseId = dragon.getDataTracker().get(EnderDragonEntity.PHASE_TYPE);
            if (phaseId >= 4 && phaseId <= 8 && isProjectile && overrideText == null) {
                finalValue = 0.0f;
                breakdown.immune = true;
            }
        }

        if (livingTarget.timeUntilRegen > 10 && !isHealing && overrideText == null) {
            breakdown.hurtWindowWarning = true;
        }

        if (isSplash && overrideText == null) {
            float effectiveHealth = livingTarget.getHealth() + livingTarget.getAbsorptionAmount();
            finalValue = isHealing
                    ? Math.min(finalValue, livingTarget.getMaxHealth() - livingTarget.getHealth())
                    : Math.min(finalValue, effectiveHealth);
        }
        if (isHealing && overrideText == null) {
            finalValue = Math.min(finalValue, livingTarget.getMaxHealth() - livingTarget.getHealth());
            breakdown.healingAmount = finalValue;
        }

        if (livingTarget instanceof WolfEntity wolf && !wolf.getBodyArmor().isEmpty() && !isSplash && !isLingering) {
            finalValue = 0.0f;
            breakdown.immune = true;
        }
        if (livingTarget instanceof EndermanEntity && isProjectile && overrideText == null) {
            finalValue = 0.0f;
            breakdown.immune = true;
        }
        if (livingTarget instanceof WitherEntity wither && wither.getHealth() <= wither.getMaxHealth() / 2.0f && isProjectile && overrideText == null) {
            finalValue = 0.0f;
            breakdown.immune = true;
        }
        if (blockedByShield) {
            finalValue = 0.0f;
            breakdown.immune = true;
        }
        if (breakdown.immune && overrideText == null) {
            finalColor = IMMUNE_COLOR;
            overrideText = "0.0";
        }

        if (overrideText == null) {
            finalColor = getColor(livingTarget, finalValue, stack, isHealing);
        }

        breakdown.cooldownWarning = !isProjectile
                && !isSplash
                && !isLingering
                && !isHealing
                && client.player.getAttackCooldownProgress(0.5f) <= 0.9f;
        breakdown.finalDamage = finalValue;
        breakdown.healing = isHealing;

        String suffix = "";
        if (isCrit && !isProjectile && !isExplosion && !isSpear && !isSplash && !isLingering) {
            suffix = "^";
        } else if ((isUsingThisHand && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || isSpear)) || isChargedCrossbow) {
            suffix = "*";
        }
        if (ModConfig.showInaccuracyWarnings && (breakdown.cooldownWarning || breakdown.hurtWindowWarning)) {
            suffix = suffix + "~";
        }

        boolean noSmoothing = isSplash || isLingering || isCrit || isHealing || (isMain ? wasCritMain : wasCritOff);
        if (isMain) {
            wasCritMain = isCrit;
        } else {
            wasCritOff = isCrit;
        }

        int configuredColor = applyConfiguredColor(finalColor);

        if (isMain) {
            mainTarget = finalValue;
            if (noSmoothing || overrideText != null) {
                mainDisplayed = mainTarget;
            } else {
                mainDisplayed = (Math.abs(mainDisplayed - mainTarget) < 0.05f)
                        ? mainTarget
                        : MathHelper.lerp(0.15f, mainDisplayed, mainTarget);
            }

            if (mainDisplayed >= 0.1f || overrideText != null) {
                if (overrideText != null) {
                    renderString(ctx, client, overrideText, configuredColor, true);
                } else {
                    renderIndicator(ctx, client, mainDisplayed, configuredColor, true, suffix, isHealing);
                }
            } else {
                mainDisplayed = 0.0f;
            }

            if (debugMenuEnabled) {
                renderDebugPanel(ctx, client, stack, targetEntity, breakdown, isCrit, isSpear, isUsingThisHand, isHealing, isMain, damageType);
            }
        } else {
            if (finalValue > 0.0f || overrideText != null || offDisplayed >= 0.1f) {
                if (noSmoothing || overrideText != null) {
                    offDisplayed = finalValue;
                } else {
                    offDisplayed = (Math.abs(offDisplayed - finalValue) < 0.05f)
                            ? finalValue
                            : MathHelper.lerp(0.20f, offDisplayed, finalValue);
                }

                if (offDisplayed >= 0.1f || overrideText != null) {
                    if (overrideText != null) {
                        renderString(ctx, client, overrideText, configuredColor, false);
                    } else {
                        renderIndicator(ctx, client, offDisplayed, configuredColor, false, suffix, isHealing);
                    }
                } else {
                    offDisplayed = 0.0f;
                }
            }
        }
    }

    private static void toggleDebugMenu(MinecraftClient client) {
        boolean keyDown = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_F7);
        if (keyDown && !debugKeyHeld) {
            debugMenuEnabled = !debugMenuEnabled;
            ModConfig.debugOverlayEnabled = debugMenuEnabled;
            ModConfig.save();
        }
        debugKeyHeld = keyDown;
    }

    private static void updateMaceFallTracking(PlayerEntity player) {
        if (player.isOnGround() || player.isClimbing() || player.isTouchingWater()) {
            maceFallStartY = -1.0;
            return;
        }
        if (maceFallStartY == -1.0 && player.getVelocity().y < -0.1) {
            maceFallStartY = player.getY();
        }
    }

    private static boolean isSpearWeapon(ItemStack stack) {
        boolean hasSpearComponents = stack.contains(DataComponentTypes.KINETIC_WEAPON) && stack.contains(DataComponentTypes.PIERCING_WEAPON);
        if (hasSpearComponents) return true;
        String itemName = stack.getItem().toString().toLowerCase(Locale.ROOT);
        return itemName.contains("spear");
    }

    private static String resolveDamageType(ItemStack stack, boolean isUsingThisHand, boolean isChargedCrossbow) {
        if (stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) {
            return "magic";
        }
        if (stack.isOf(Items.CROSSBOW) && hasExplosiveFirework(stack)) {
            return "explosion";
        }
        boolean isProjectile = stack.isOf(Items.BOW)
                || stack.isOf(Items.CROSSBOW)
                || stack.isOf(Items.SNOWBALL)
                || (stack.isOf(Items.TRIDENT) && isUsingThisHand)
                || isChargedCrossbow;
        return isProjectile ? "projectile" : "melee";
    }

    private static ReachProfile getReachProfile(MinecraftClient client, ItemStack stack, boolean isSpear, boolean isUsingThisHand) {
        if (client.player == null) return new ReachProfile(3.5f, false, 3.5f);
        float baseReach = (float) client.player.getAttributeValue(EntityAttributes.ENTITY_INTERACTION_RANGE);

        if (ModConfig.enableVerticalMaceReach && stack.isOf(Items.MACE) && client.player.fallDistance > 0) {
            return new ReachProfile(100.0f, true, baseReach);
        }
        if (stack.isOf(Items.BOW) || stack.isOf(Items.TRIDENT) || stack.isOf(Items.SNOWBALL)) {
            return new ReachProfile((isUsingThisHand ? 45.0f : baseReach), false, baseReach);
        }
        if (stack.isOf(Items.CROSSBOW)) {
            float reach = ModConfig.enableExtendedCrossbowReach ? 45.0f : baseReach;
            return new ReachProfile(reach, false, baseReach);
        }
        if (isSpear && isUsingThisHand) {
            return new ReachProfile(100.0f, false, baseReach + 1.5f);
        }
        if (isSpear) {
            return new ReachProfile(baseReach + 1.5f, false, baseReach + 1.5f);
        }
        return new ReachProfile(baseReach, false, baseReach);
    }

    private static Entity getTarget(MinecraftClient client, ReachProfile profile) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.world == null) return null;

        if (profile.maceVerticalOnly) {
            if (client.crosshairTarget instanceof EntityHitResult hit) {
                Entity crosshairEntity = hit.getEntity();
                if (crosshairEntity != null
                        && withinHorizontalLimit(camera, crosshairEntity, profile.horizontalLimit)
                        && hasClearLineOfSight(camera, crosshairEntity)) {
                    return crosshairEntity;
                }
            }

            Box scanBox = camera.getBoundingBox().expand(profile.horizontalLimit, profile.maxDistance, profile.horizontalLimit);
            return client.world.getOtherEntities(camera, scanBox, entity -> !entity.isSpectator() && entity.canHit() && withinHorizontalLimit(camera, entity, profile.horizontalLimit))
                    .stream()
                    .filter(entity -> hasClearLineOfSight(camera, entity))
                    .min(Comparator.comparingDouble(entity -> Math.abs(entity.getY() - camera.getY())))
                    .orElse(null);
        }

        Vec3d start = camera.getEyePos();
        Vec3d rotation = camera.getRotationVec(1.0f);
        Vec3d end = start.add(rotation.multiply(profile.maxDistance));
        BlockHitResult blockHit = client.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                camera
        ));
        double maxDistanceSquared = profile.maxDistance * profile.maxDistance;
        double firstBlockDistanceSquared = maxDistanceSquared;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            firstBlockDistanceSquared = Math.min(firstBlockDistanceSquared, start.squaredDistanceTo(blockHit.getPos()));
        }

        if (client.crosshairTarget instanceof EntityHitResult hit) {
            Entity crosshairEntity = hit.getEntity();
            if (isValidRayTarget(camera, crosshairEntity, start, end, firstBlockDistanceSquared, maxDistanceSquared)) {
                return crosshairEntity;
            }
        }

        EntityHitResult raycast = ProjectileUtil.raycast(
                camera,
                start,
                end,
                camera.getBoundingBox().stretch(rotation.multiply(profile.maxDistance)).expand(0.35d),
                entity -> !entity.isSpectator() && entity.canHit(),
                firstBlockDistanceSquared
        );
        if (raycast != null && isValidRayTarget(camera, raycast.getEntity(), start, end, firstBlockDistanceSquared, maxDistanceSquared)) {
            return raycast.getEntity();
        }

        Box searchBox = camera.getBoundingBox().stretch(rotation.multiply(profile.maxDistance)).expand(0.35d);
        Entity best = null;
        double bestDistanceSquared = firstBlockDistanceSquared;
        for (Entity candidate : client.world.getOtherEntities(camera, searchBox, entity -> !entity.isSpectator() && entity.canHit())) {
            double candidateDistanceSquared = getRayHitDistanceSquared(start, end, candidate);
            if (candidateDistanceSquared < 0.0d || candidateDistanceSquared > bestDistanceSquared + 1.0E-5) {
                continue;
            }
            if (!hasClearLineOfSight(camera, candidate)) {
                continue;
            }
            bestDistanceSquared = candidateDistanceSquared;
            best = candidate;
        }
        return best;
    }

    private static boolean isValidRayTarget(Entity source, Entity target, Vec3d start, Vec3d end, double blockDistanceSquared, double maxDistanceSquared) {
        if (target == null || target.isSpectator() || !target.canHit()) return false;
        double hitDistanceSquared = getRayHitDistanceSquared(start, end, target);
        if (hitDistanceSquared < 0.0d) return false;
        if (hitDistanceSquared > maxDistanceSquared + 1.0E-5) return false;
        if (hitDistanceSquared > blockDistanceSquared + 1.0E-5) return false;
        return hasClearLineOfSight(source, target);
    }

    private static double getRayHitDistanceSquared(Vec3d start, Vec3d end, Entity entity) {
        Box entityBox = entity.getBoundingBox().expand(entity.getTargetingMargin());
        if (entityBox.contains(start)) {
            return 0.0d;
        }
        var hit = entityBox.raycast(start, end);
        return hit.map(vec3d -> start.squaredDistanceTo(vec3d)).orElse(-1.0d);
    }

    private static boolean withinHorizontalLimit(Entity source, Entity target, float horizontalLimit) {
        double dx = source.getX() - target.getX();
        double dz = source.getZ() - target.getZ();
        return (dx * dx + dz * dz) <= (horizontalLimit * horizontalLimit);
    }

    private static boolean hasClearLineOfSight(Entity source, Entity target) {
        if (source == null || target == null) return false;
        Vec3d start = source.getEyePos();
        Vec3d center = target.getBoundingBox().getCenter();
        if (isRayClear(source, start, center)) {
            return true;
        }
        if (target instanceof LivingEntity livingTarget) {
            return isRayClear(source, start, livingTarget.getEyePos());
        }
        return false;
    }

    private static boolean isRayClear(Entity source, Vec3d start, Vec3d end) {
        BlockHitResult blockHit = source.getEntityWorld().raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                source
        ));
        if (blockHit.getType() == HitResult.Type.MISS) {
            return true;
        }
        double blockDistanceSquared = start.squaredDistanceTo(blockHit.getPos());
        double targetDistanceSquared = start.squaredDistanceTo(end);
        return blockDistanceSquared + 1.0E-5 >= targetDistanceSquared;
    }

    private static boolean isVanillaCrit(PlayerEntity player, Entity target) {
        return player.fallDistance > 0.0f
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !player.hasVehicle()
                && !player.isSprinting()
                && target instanceof LivingEntity
                && player.getAttackCooldownProgress(0.5f) > 0.9f;
    }

    private static float calculateRawPhysical(
            MinecraftClient client,
            LivingEntity target,
            ItemStack stack,
            boolean isCrit,
            boolean isSpear,
            boolean isUsingThisHand,
            DamageBreakdown breakdown
    ) {
        PlayerEntity player = client.player;

        if (stack.isOf(Items.BOW)) {
            if (!(player.isUsingItem() && player.getActiveItem() == stack)) return 0.0f;
            int ticks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
            float pull = Math.min((float) ticks / 20.0f, 1.0f);
            pull = (pull * pull + pull * 2.0f) / 3.0f;
            float base = (pull * 7.0f) + 2.0f;
            breakdown.baseDamage = base;
            breakdown.preMitigation = applyPowerEnchantment(client, stack, base, breakdown);
            return breakdown.preMitigation;
        }

        if (stack.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged == null || charged.isEmpty()) return 0.0f;

            ItemStack projectile = charged.getProjectiles().get(0);
            if (projectile.isOf(Items.FIREWORK_ROCKET)) {
                FireworksComponent fireworks = projectile.get(DataComponentTypes.FIREWORKS);
                float fireworkDamage = (fireworks != null && !fireworks.explosions().isEmpty())
                        ? 5.0f + (fireworks.explosions().size() * 2.0f)
                        : 0.0f;
                breakdown.baseDamage = fireworkDamage;
                breakdown.preMitigation = fireworkDamage;
                return fireworkDamage;
            }

            float base = 9.0f;
            breakdown.baseDamage = base;
            breakdown.preMitigation = applyPowerEnchantment(client, stack, base, breakdown);
            return breakdown.preMitigation;
        }

        float baseDamage = getWeaponBaseDamage(player, stack);
        float strengthBonus = player.hasStatusEffect(StatusEffects.STRENGTH)
                ? 3.0f * (player.getStatusEffect(StatusEffects.STRENGTH).getAmplifier() + 1)
                : 0.0f;
        float weaknessPenalty = player.hasStatusEffect(StatusEffects.WEAKNESS)
                ? -4.0f * (player.getStatusEffect(StatusEffects.WEAKNESS).getAmplifier() + 1)
                : 0.0f;

        breakdown.baseDamage = baseDamage;
        breakdown.strengthBonus = strengthBonus;
        breakdown.weaknessPenalty = weaknessPenalty;

        float total = baseDamage + strengthBonus + weaknessPenalty;

        if (stack.isOf(Items.MACE)) {
            float fallDistance = (float) (maceFallStartY != -1.0 ? Math.max(0.0, maceFallStartY - player.getY()) : 0.0);
            breakdown.maceFallDistance = fallDistance;

            if (fallDistance > 1.5f && !player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
                float smashBonus = (fallDistance <= 3.0f)
                        ? fallDistance * 4.0f
                        : (fallDistance <= 8.0f
                        ? 12.0f + ((fallDistance - 3.0f) * 2.0f)
                        : 22.0f + (fallDistance - 8.0f));
                var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                int density = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.DENSITY), stack);
                smashBonus += density * 0.5f * fallDistance;
                breakdown.maceFallBonus = smashBonus;
                total += smashBonus;
            }
        }

        if (isSpear && isUsingThisHand) {
            float spearBonus = calculateSpearVelocityBonus(player, target, stack, breakdown);
            breakdown.spearVelocityBonus = spearBonus;
            total += spearBonus;
        }

        if (isCrit) {
            breakdown.critBonus = total * 0.5f;
            total *= 1.5f;
            breakdown.critMultiplier = 1.5f;
        } else {
            breakdown.critBonus = 0.0f;
            breakdown.critMultiplier = 1.0f;
        }

        breakdown.preMitigation = total;
        return total;
    }

    private static float getWeaponBaseDamage(PlayerEntity player, ItemStack stack) {
        double baseAttackDamage = player.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE);
        double[] calculatedFromStack = {baseAttackDamage};

        // Includes item component modifiers and enchantment-added attribute modifiers.
        stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (!attribute.matches(EntityAttributes.ATTACK_DAMAGE)) return;

            if (modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                calculatedFromStack[0] += modifier.value();
            } else if (modifier.operation() == EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                calculatedFromStack[0] += modifier.value() * baseAttackDamage;
            } else if (modifier.operation() == EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                calculatedFromStack[0] += modifier.value() * calculatedFromStack[0];
            }
        });

        if (stack == player.getMainHandStack()) {
            double liveMainHandValue = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
            return (float) Math.max(liveMainHandValue, calculatedFromStack[0]);
        }
        return (float) calculatedFromStack[0];
    }

    private static float calculateSpearVelocityBonus(PlayerEntity player, LivingEntity target, ItemStack stack, DamageBreakdown breakdown) {
        KineticWeaponComponent kinetic = stack.get(DataComponentTypes.KINETIC_WEAPON);
        if (kinetic == null) return 0.0f;

        int useTicks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
        if (useTicks < kinetic.delayTicks()) {
            return 0.0f;
        }
        int activeTicks = useTicks - kinetic.delayTicks();

        Vec3d facing = player.getRotationVec(1.0f);
        double attackerForwardSpeed = facing.dotProduct(KineticWeaponComponent.getAmplifiedMovement(player));
        double targetForwardSpeed = facing.dotProduct(KineticWeaponComponent.getAmplifiedMovement(target));
        double relativeForwardSpeed = Math.max(0.0, attackerForwardSpeed - targetForwardSpeed);

        boolean canDealKineticDamage = kinetic.damageConditions()
                .map(condition -> condition.isSatisfied(activeTicks, attackerForwardSpeed, relativeForwardSpeed, 1.0))
                .orElse(true);

        // If vanilla condition metadata is missing or mismatched on a custom spear, keep predicting from velocity.
        if (!canDealKineticDamage && stack.contains(DataComponentTypes.KINETIC_WEAPON)) {
            canDealKineticDamage = true;
        }

        breakdown.spearForwardSpeed = (float) attackerForwardSpeed;
        breakdown.spearRelativeSpeed = (float) relativeForwardSpeed;

        if (!canDealKineticDamage) {
            return 0.0f;
        }
        return (float) MathHelper.floor(relativeForwardSpeed * kinetic.damageMultiplier());
    }

    private static float applyFinalReductions(
            MinecraftClient client,
            LivingEntity target,
            ItemStack stack,
            float physicalDamage,
            float magicDamage,
            boolean isProjectile,
            boolean isExplosion,
            boolean isHealing,
            DamageBreakdown breakdown
    ) {
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        float enchantBonus = 0.0f;
        int enchantmentLevel = breakdown.enchantmentLevel;

        int sharpness = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SHARPNESS), stack);
        if (sharpness > 0) {
            enchantBonus += 0.5f * sharpness + 0.5f;
            enchantmentLevel = Math.max(enchantmentLevel, sharpness);
        }
        int smite = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.SMITE), stack);
        if (smite > 0 && target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
            enchantBonus += smite * 2.5f;
            enchantmentLevel = Math.max(enchantmentLevel, smite);
        }
        int bane = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BANE_OF_ARTHROPODS), stack);
        if (bane > 0 && target.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            enchantBonus += bane * 2.5f;
            enchantmentLevel = Math.max(enchantmentLevel, bane);
        }
        int impaling = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.IMPALING), stack);
        if (impaling > 0 && target.getType().isIn(EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            enchantBonus += impaling * 2.5f;
            enchantmentLevel = Math.max(enchantmentLevel, impaling);
        }

        breakdown.enchantmentBonus += enchantBonus;
        breakdown.enchantmentLevel = enchantmentLevel;

        float armor = (float) target.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), stack);
        if (breach > 0) {
            armor *= (1.0f - breach * 0.15f);
            toughness *= (1.0f - breach * 0.15f);
        }

        breakdown.armor = armor;
        breakdown.toughness = toughness;

        float armorInput = physicalDamage + enchantBonus;
        breakdown.initialOutput = armorInput + magicDamage;
        breakdown.armorInput = armorInput;

        float afterArmor = DamageUtil.getDamageLeft(target, armorInput, target.getDamageSources().generic(), armor, toughness);
        breakdown.afterArmor = afterArmor;
        breakdown.armorPenalty = Math.max(0.0f, armorInput - afterArmor);
        breakdown.armorPenaltyPercent = armorInput > 0.0f ? (breakdown.armorPenalty / armorInput) * 100.0f : 0.0f;

        float resistanceMultiplier = 1.0f;
        int resistanceLevel = 0;
        if (!isHealing && target.hasStatusEffect(StatusEffects.RESISTANCE)) {
            resistanceLevel = target.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            resistanceMultiplier = Math.max(0.0f, 1.0f - resistanceLevel * 0.2f);
        }
        breakdown.resistanceMultiplier = resistanceMultiplier;
        breakdown.resistanceLevel = resistanceLevel;

        float resistanceInput = isHealing ? afterArmor : (afterArmor + magicDamage);
        breakdown.resistanceInput = resistanceInput;
        float afterResistance = resistanceInput * resistanceMultiplier;
        breakdown.resistancePenalty = Math.max(0.0f, resistanceInput - afterResistance);
        breakdown.resistancePenaltyPercent = resistanceInput > 0.0f ? (breakdown.resistancePenalty / resistanceInput) * 100.0f : 0.0f;

        int epf = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armorPiece = target.getEquippedStack(slot);
            if (!armorPiece.isEmpty()) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), armorPiece);
                if (isProjectile) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), armorPiece) * 2;
                if (isExplosion) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), armorPiece) * 2;
            }
        }
        breakdown.epf = epf;

        float protectionMultiplier = 1.0f - (Math.min(20.0f, (float) epf) * 0.04f);
        breakdown.protectionMultiplier = protectionMultiplier;

        float afterProtection = afterResistance * protectionMultiplier;
        breakdown.protectionPenalty = Math.max(0.0f, afterResistance - afterProtection);
        breakdown.protectionPenaltyPercent = afterResistance > 0.0f ? (breakdown.protectionPenalty / afterResistance) * 100.0f : 0.0f;

        float finalDamage = isHealing
                ? afterProtection + magicDamage
                : afterProtection;
        breakdown.finalDamage = finalDamage;
        return finalDamage;
    }

    private static PotionResult calculatePotionResult(MinecraftClient client, LivingEntity target, ItemStack stack, boolean lingering) {
        PotionResult result = new PotionResult();
        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return result;

        boolean undead = target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
        boolean immuneToPoison = undead
                || target instanceof EnderDragonEntity
                || target instanceof WitherEntity
                || target.getType().equals(EntityType.SPIDER)
                || target.getType().equals(EntityType.CAVE_SPIDER)
                || target.getType().equals(EntityType.BOGGED);
        var effects = contents.potion().isPresent() ? contents.potion().get().value().getEffects() : contents.getEffects();

        int epf = 0;
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack piece = target.getEquippedStack(slot);
            if (!piece.isEmpty()) epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), piece);
        }
        float magicReduction = 1.0f - (Math.min(20.0f, (float) epf) * 0.04f);
        float resistanceReduction = 1.0f;
        if (target.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int amplifier = target.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            resistanceReduction = Math.max(0.0f, 1.0f - amplifier * 0.2f);
        }

        for (StatusEffectInstance effect : effects) {
            boolean isHeal = effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH);
            boolean isHarm = effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE);
            boolean isPoison = effect.getEffectType().equals(StatusEffects.POISON);
            boolean isRegen = effect.getEffectType().equals(StatusEffects.REGENERATION);
            String heartText = effect.getAmplifier() > 0 ? "💕" : "❤";

            if (target instanceof EndermanEntity && isHarm) {
                result.value = 0.0f;
                continue;
            }

            if (isPoison) {
                if (!immuneToPoison) {
                    result.overrideText = "-" + heartText;
                    result.color = 0xFF4E9331;
                }
                continue;
            }
            if (isRegen) {
                if (!undead && !(target instanceof EnderDragonEntity)) {
                    result.overrideText = "+" + heartText;
                    result.color = 0xFF00FF00;
                }
                continue;
            }
            if (lingering) {
                if (isHeal) {
                    result.overrideText = undead ? "-" + heartText : "+" + heartText;
                    result.color = undead ? 0xFF990000 : 0xFF00FF00;
                } else if (isHarm) {
                    result.overrideText = undead ? "+" + heartText : "-" + heartText;
                    result.color = undead ? 0xFF00FF00 : 0xFF990000;
                }
                continue;
            }

            if (isHeal) {
                float value = (undead ? 6.0f : 4.0f) * (effect.getAmplifier() + 1);
                result.healing = !undead;
                result.value = result.healing ? value : value * magicReduction * resistanceReduction;
            } else if (isHarm) {
                float value = (undead ? 4.0f : 6.0f) * (effect.getAmplifier() + 1);
                result.healing = undead;
                result.value = result.healing ? value : value * magicReduction * resistanceReduction;
            }
        }
        return result;
    }

    private static boolean isBlockedByShield(Entity attacker, LivingEntity target) {
        if (!target.isBlocking()) return false;
        Vec3d fromTargetToAttacker = attacker.getEntityPos().subtract(target.getEntityPos());
        Vec3d horizontalDirection = new Vec3d(fromTargetToAttacker.x, 0.0, fromTargetToAttacker.z);
        if (horizontalDirection.lengthSquared() <= 1.0E-6) {
            return false;
        }
        Vec3d lookDirection = target.getRotationVector(0.0f, target.getHeadYaw());
        return horizontalDirection.normalize().dotProduct(lookDirection) > 0.0;
    }

    private static float calculateInteractionHealing(LivingEntity target, ItemStack stack) {
        if (target.getHealth() >= target.getMaxHealth()) {
            return 0.0f;
        }

        if (target instanceof IronGolemEntity && stack.isOf(Items.IRON_INGOT)) {
            return 25.0f;
        }

        if (target instanceof WolfEntity wolf && wolf.isTamed() && stack.isIn(ItemTags.WOLF_FOOD)) {
            return 2.0f * getFoodNutritionOrDefault(stack, 1.0f);
        }

        if (target instanceof CatEntity cat && cat.isTamed() && stack.isIn(ItemTags.CAT_FOOD)) {
            return getFoodNutritionOrDefault(stack, 1.0f);
        }

        if (target instanceof CamelEntity && stack.isIn(ItemTags.CAMEL_FOOD)) {
            return 2.0f;
        }

        if (target instanceof LlamaEntity) {
            if (!stack.isIn(ItemTags.LLAMA_FOOD)) return 0.0f;
            if (stack.isOf(Items.WHEAT)) return 2.0f;
            if (stack.isOf(Blocks.HAY_BLOCK.asItem())) return 10.0f;
            return 0.0f;
        }

        if (target instanceof AbstractNautilusEntity nautilus) {
            boolean isHealingFood = nautilus.isBaby() || nautilus.isTamed()
                    ? stack.isIn(ItemTags.NAUTILUS_FOOD)
                    : stack.isIn(ItemTags.NAUTILUS_TAMING_ITEMS);
            if (!isHealingFood) return 0.0f;
            return 2.0f * getFoodNutritionOrDefault(stack, 0.5f);
        }

        if (target instanceof AbstractHorseEntity && stack.isIn(ItemTags.HORSE_FOOD)) {
            if (stack.isOf(Items.WHEAT)) return 2.0f;
            if (stack.isOf(Items.SUGAR)) return 1.0f;
            if (stack.isOf(Blocks.HAY_BLOCK.asItem())) return 20.0f;
            if (stack.isOf(Items.APPLE)) return 3.0f;
            if (stack.isOf(Items.RED_MUSHROOM)) return 3.0f;
            if (stack.isOf(Items.CARROT)) return 3.0f;
            if (stack.isOf(Items.GOLDEN_CARROT)) return 4.0f;
            if (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) return 10.0f;
        }

        return 0.0f;
    }

    private static float getFoodNutritionOrDefault(ItemStack stack, float fallback) {
        if (stack.contains(DataComponentTypes.FOOD) && stack.get(DataComponentTypes.FOOD) != null) {
            return stack.get(DataComponentTypes.FOOD).nutrition();
        }
        return fallback;
    }

    private static boolean isPotentialInteractionHealingItem(ItemStack stack) {
        return stack.isOf(Items.IRON_INGOT)
                || stack.isIn(ItemTags.WOLF_FOOD)
                || stack.isIn(ItemTags.CAT_FOOD)
                || stack.isIn(ItemTags.CAMEL_FOOD)
                || stack.isIn(ItemTags.LLAMA_FOOD)
                || stack.isIn(ItemTags.HORSE_FOOD)
                || stack.isIn(ItemTags.NAUTILUS_FOOD)
                || stack.isIn(ItemTags.NAUTILUS_TAMING_ITEMS);
    }

    private static boolean isMagicHealing(ItemStack bow, PlayerEntity player, boolean targetIsUndead) {
        ItemStack projectile = bow.isOf(Items.BOW)
                ? getActiveArrow(player)
                : (bow.get(DataComponentTypes.CHARGED_PROJECTILES) != null && !bow.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty()
                ? bow.get(DataComponentTypes.CHARGED_PROJECTILES).getProjectiles().get(0)
                : ItemStack.EMPTY);
        if (projectile.isEmpty() || !projectile.contains(DataComponentTypes.POTION_CONTENTS)) return false;

        for (StatusEffectInstance effect : projectile.get(DataComponentTypes.POTION_CONTENTS).getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && !targetIsUndead) return true;
            if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && targetIsUndead) return true;
        }
        return false;
    }

    private static float calculateMagicCap(PlayerEntity player, LivingEntity target, ItemStack bow, boolean active) {
        if (!active) return 0.0f;

        ItemStack projectile = bow.isOf(Items.BOW)
                ? getActiveArrow(player)
                : (bow.get(DataComponentTypes.CHARGED_PROJECTILES) != null && !bow.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty()
                ? bow.get(DataComponentTypes.CHARGED_PROJECTILES).getProjectiles().get(0)
                : ItemStack.EMPTY);
        if (projectile.isEmpty() || !projectile.contains(DataComponentTypes.POTION_CONTENTS)) return 0.0f;

        boolean undead = target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
        for (StatusEffectInstance effect : projectile.get(DataComponentTypes.POTION_CONTENTS).getEffects()) {
            if ((effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && !undead)
                    || (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && undead)) {
                return 6.0f * (effect.getAmplifier() + 1);
            }
            if ((effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH) && !undead)
                    || (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE) && undead)) {
                return 6.0f * (effect.getAmplifier() + 1);
            }
        }
        return 0.0f;
    }

    private static ItemStack getActiveArrow(PlayerEntity player) {
        if (player.getOffHandStack().getItem() instanceof ArrowItem) return player.getOffHandStack();
        if (player.getMainHandStack().getItem() instanceof ArrowItem) return player.getMainHandStack();
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).getItem() instanceof ArrowItem) {
                return player.getInventory().getStack(i);
            }
        }
        return ItemStack.EMPTY;
    }

    private static float applyPowerEnchantment(MinecraftClient client, ItemStack stack, float baseDamage, DamageBreakdown breakdown) {
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int level = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.POWER), stack);
        if (level <= 0) return baseDamage;
        float bonus = 0.5f * level + 0.5f;
        breakdown.enchantmentLevel = Math.max(breakdown.enchantmentLevel, level);
        breakdown.enchantmentBonus += bonus;
        return baseDamage + bonus;
    }

    private static boolean hasExplosiveFirework(ItemStack stack) {
        ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        return charged != null
                && !charged.isEmpty()
                && charged.getProjectiles().get(0).isOf(Items.FIREWORK_ROCKET)
                && charged.getProjectiles().get(0).contains(DataComponentTypes.FIREWORKS)
                && !charged.getProjectiles().get(0).get(DataComponentTypes.FIREWORKS).explosions().isEmpty();
    }

    private static int getColor(LivingEntity target, float damage, ItemStack stack, boolean healing) {
        if (healing) return 0xFF00FF00;
        float health = target.getHealth() + target.getAbsorptionAmount();
        if (stack.isOf(Items.SPLASH_POTION)) return healing ? 0xFF00FF00 : 0xFF990000;
        return damage >= health
                ? (isEnemyHoldingTotem(target) ? 0xFFF2FF00 : 0xFFFF0000)
                : 0xFFFFFFFF;
    }

    private static boolean isEnemyHoldingTotem(LivingEntity entity) {
        return entity.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.TOTEM_OF_UNDYING)
                || entity.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static int applyConfiguredColor(int color) {
        int alpha = MathHelper.clamp(ModConfig.opacity, 0, 255);
        int rgb = color & 0x00FFFFFF;
        if ((rgb == 0x00FFFFFF) && ModConfig.indicatorColor != null) {
            rgb = ModConfig.indicatorColor.getRGB() & 0x00FFFFFF;
        }
        return (alpha << 24) | rgb;
    }

    private static void renderIndicator(DrawContext ctx, MinecraftClient client, float damage, int color, boolean isMain, String suffix, boolean isHealing) {
        if (!suffix.equals("X") && damage < 0.1f) return;
        String valueText = suffix.equals("X")
                ? "X"
                : (isHealing ? "+" : "") + String.format(Locale.ROOT, "%.1f", damage) + suffix;
        renderString(ctx, client, valueText, color, isMain);
    }

    private static void renderString(DrawContext ctx, MinecraftClient client, String text, int color, boolean isMain) {
        int centerX = client.getWindow().getScaledWidth() / 2;
        int centerY = (client.getWindow().getScaledHeight() / 2) - 4;
        int x = isMain ? centerX + 15 : centerX - 15 - client.textRenderer.getWidth(text);
        ctx.drawTextWithShadow(client.textRenderer, text, x, centerY, color);
    }

    private static void renderDebugPanel(
            DrawContext ctx,
            MinecraftClient client,
            ItemStack stack,
            Entity target,
            DamageBreakdown breakdown,
            boolean isCrit,
            boolean isSpear,
            boolean isUsingThisHand,
            boolean isHealing,
            boolean isMain,
            String damageType
    ) {
        int x = 8;
        int y = 8;
        int neutralColor = 0xFFE6E6E6;
        int inputOutputColor = 0xFF8FFF48;
        int finalOutputColor = 0xFFFFA640;
        int immuneDebugColor = applyConfiguredColor(IMMUNE_COLOR);
        float strWeakBonus = breakdown.strengthBonus + breakdown.weaknessPenalty;
        String spearRelativeSpeed = isSpear ? String.format(Locale.ROOT, "%.2fm/s", breakdown.spearRelativeSpeed) : "n/a";
        String healingAmount = isHealing ? String.format(Locale.ROOT, "%.2f", breakdown.healingAmount) : "n/a";

        drawDebugLine(ctx, client, x, y, "B4's Pre-Damage Indicator Debug Mode [F7]", 0xFFFFFFFF);
        y += 10;
        drawDebugLine(ctx, client, x, y, "Item: " + stack.getName().getString(), neutralColor);
        y += 10;
        String targetType = target == null ? "none" : String.valueOf(Registries.ENTITY_TYPE.getId(target.getType()));
        drawDebugLine(ctx, client, x, y, "Target: " + targetType, neutralColor);
        y += 10;
        if (target instanceof LivingEntity livingTarget) {
            drawDebugLine(
                    ctx,
                    client,
                    x,
                    y,
                    String.format(Locale.ROOT, "Target HP: %.1f/%.1f", livingTarget.getHealth() + livingTarget.getAbsorptionAmount(), livingTarget.getMaxHealth()),
                    neutralColor
            );
        } else {
            drawDebugLine(ctx, client, x, y, "Target HP: n/a", neutralColor);
        }
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Initial input: %.2f  str/weak bonus: %s", breakdown.baseDamage, formatSignedValue(strWeakBonus)), neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Crit: %s (x%.1f)  Bonus: %s", isCrit, breakdown.critMultiplier, formatSignedValue(breakdown.critBonus)), neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Ench lvl: %d  Bonus: %s", breakdown.enchantmentLevel, formatSignedValue(breakdown.enchantmentBonus)), neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Magic bonus: %s", formatSignedValue(breakdown.magicBonus)), neutralColor);
        y += 10;
        drawDebugLine(
                ctx,
                client,
                x,
                y,
                String.format(
                        Locale.ROOT,
                        "Is Spear: %s  Vel: p:(%.2fm/s) t:(%s)  Bonus: %s",
                        isSpear,
                        breakdown.spearForwardSpeed,
                        spearRelativeSpeed,
                        formatSignedValue(breakdown.spearVelocityBonus)
                ),
                neutralColor
        );
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Is Mace: %s  fallDist: %.2f  Bonus: %s", stack.isOf(Items.MACE), breakdown.maceFallDistance, formatSignedValue(breakdown.maceFallBonus)), neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, "Is offhand: " + !isMain + "  type: " + damageType + "  Is using: " + isUsingThisHand, neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, String.format(Locale.ROOT, "Final Input/Initial Output: %.2f", breakdown.initialOutput), inputOutputColor);
        y += 10;
        drawDebugLine(
                ctx,
                client,
                x,
                y,
                String.format(
                        Locale.ROOT,
                        "Armor: %.2f  Tough: %.2f  Penalty: (-%d%%) -%.2f",
                        breakdown.armor,
                        breakdown.toughness,
                        Math.round(breakdown.armorPenaltyPercent),
                        breakdown.armorPenalty
                ),
                neutralColor
        );
        y += 10;
        drawDebugLine(
                ctx,
                client,
                x,
                y,
                String.format(
                        Locale.ROOT,
                        "Resistance lvl: %d (-%d%%)  Penalty: -%.2f",
                        breakdown.resistanceLevel,
                        Math.round(breakdown.resistancePenaltyPercent),
                        breakdown.resistancePenalty
                ),
                neutralColor
        );
        y += 10;
        drawDebugLine(
                ctx,
                client,
                x,
                y,
                String.format(
                        Locale.ROOT,
                        "EPF: %d  (-%d%%)  Penalty: -%.2f",
                        breakdown.epf,
                        Math.round(breakdown.protectionPenaltyPercent),
                        breakdown.protectionPenalty
                ),
                neutralColor
        );
        y += 10;
        drawDebugSegments(
                ctx,
                client,
                x,
                y,
                new DebugSegment(String.format(Locale.ROOT, "Final Output: %.2f  ", breakdown.finalDamage), finalOutputColor),
                new DebugSegment("Is immune: " + breakdown.immune, immuneDebugColor)
        );
        y += 10;
        drawDebugLine(ctx, client, x, y, "Is Healing: " + isHealing + "  Amount: " + healingAmount, neutralColor);
        y += 10;
        drawDebugLine(ctx, client, x, y, "Cooldown: " + breakdown.cooldownWarning + "  HurtWindow: " + breakdown.hurtWindowWarning, 0xFFFFC04A);
    }

    private static void drawDebugLine(DrawContext ctx, MinecraftClient client, int x, int y, String text, int color) {
        ctx.drawTextWithShadow(client.textRenderer, text, x, y, color);
    }

    private static void drawDebugSegments(DrawContext ctx, MinecraftClient client, int x, int y, DebugSegment... segments) {
        int cursorX = x;
        for (DebugSegment segment : segments) {
            ctx.drawTextWithShadow(client.textRenderer, segment.text, cursorX, y, segment.color);
            cursorX += client.textRenderer.getWidth(segment.text);
        }
    }

    private static String formatSignedValue(float value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private static String buildMainWeaponKey(PlayerEntity player, ItemStack stack) {
        return player.getInventory().getSelectedSlot() + ":" + Registries.ITEM.getId(stack.getItem());
    }

    private static void resetMainSmoothingState() {
        mainDisplayed = 0.0f;
        mainTarget = 0.0f;
        mainLastTargetTimestampMs = 0L;
    }

    private static void expireOrKeepMainSmoothingState() {
        long now = System.currentTimeMillis();
        if (mainLastTargetTimestampMs <= 0L || now - mainLastTargetTimestampMs > TARGET_LOSS_GRACE_MS) {
            resetMainSmoothingState();
        }
    }

    private record ReachProfile(float maxDistance, boolean maceVerticalOnly, float horizontalLimit) {
    }

    private record DebugSegment(String text, int color) {
    }

    private static class DamageBreakdown {
        float baseDamage;
        float strengthBonus;
        float weaknessPenalty;
        float critMultiplier;
        float critBonus;
        float maceFallDistance;
        float maceFallBonus;
        float spearForwardSpeed;
        float spearRelativeSpeed;
        float spearVelocityBonus;
        float preMitigation;
        float initialOutput;
        int enchantmentLevel;
        float enchantmentBonus;
        float magicBonus;
        float armor;
        float toughness;
        float armorInput;
        float armorPenalty;
        float armorPenaltyPercent;
        float afterArmor;
        int resistanceLevel;
        float resistanceMultiplier = 1.0f;
        float resistanceInput;
        float resistancePenalty;
        float resistancePenaltyPercent;
        int epf;
        float protectionMultiplier = 1.0f;
        float protectionPenalty;
        float protectionPenaltyPercent;
        boolean immune;
        boolean healing;
        boolean cooldownWarning;
        boolean hurtWindowWarning;
        float finalDamage;
        float healingAmount;
    }

    private static class PotionResult {
        float value = 0.0f;
        int color = 0xFFFFFFFF;
        String overrideText = null;
        boolean healing = false;
    }
}
