package com.b4.predamage.client;

import com.b4.predamage.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
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
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreakingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AbstractNautilusEntity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.passive.ArmadilloEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
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
import net.minecraft.world.GameMode;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.Locale;

public class PreDamageHud {
    private static final int IMMUNE_COLOR = 0xFF0000AA;

    private static float mainDisplayed = 0.0f;
    private static float offDisplayed = 0.0f;
    private static float mainTarget = 0.0f;
    private static boolean wasCritMain = false;
    private static boolean wasCritOff = false;

    // Smash lifecycle tracker (vanilla-mirrored)
    private static final SmashState SMASH_STATE = new SmashState();

    private static boolean debugMenuEnabled = false;
    private static boolean debugKeyHeld = false;

    private static final long TARGET_LOSS_GRACE_MS = 2000L;
    private static long mainLastTargetTimestampMs = 0L;
    private static String mainLastWeaponKey = "";

    public static void onEntityAttack(PlayerEntity player, Hand hand, Entity target) {
        if (player == null || target == null || hand != Hand.MAIN_HAND) return;
        ItemStack stack = player.getStackInHand(hand);
        if (!stack.isOf(Items.MACE)) return;

        SMASH_STATE.tick(player);
        if (SMASH_STATE.consumeSmash(player)) {
            resetMainSmoothingState();
        }
    }

    public static void processHand(MinecraftClient client, DrawContext ctx, ItemStack stack, boolean isMain) {
        if (client.player == null || client.world == null) {
            if (isMain) {
                SMASH_STATE.reset();
            }
            return;
        }
        if (isMain) {
            SMASH_STATE.tick(client);
        }
        if (!ModConfig.modEnabled) return;

        debugMenuEnabled = ModConfig.debugOverlayEnabled;
        toggleDebugMenu(client);
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

        if (rawHit instanceof PlayerEntity playerTarget && isUntargetablePlayer(client, playerTarget)) {
            if (isMain) {
                expireOrKeepMainSmoothingState(stack);
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
                || targetEntity instanceof ArmorStandEntity) {
            if (isMain) {
                expireOrKeepMainSmoothingState(stack);
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
        boolean isDeflectableProjectile = isProjectile || isExplosion || isSplash || isLingering;
        Vec3d targetHitPos = getTargetHitPos(client, rawHit != null ? rawHit : targetEntity, reachProfile);
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

        if (livingTarget instanceof CreakingEntity || isDeflectedProjectileTarget(livingTarget, isDeflectableProjectile)) {
            finalValue = 0.0f;
            breakdown.immune = true;
        } else if (overrideText == null) {
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
                if (isExplosion) {
                    phys = calculateFireworkExplosionDamage(client, livingTarget, stack, targetHitPos);
                    breakdown.baseDamage = phys;
                    breakdown.preMitigation = phys;
                }

                MagicEffectResult magic = calculateArrowInstantEffect(livingTarget, stack, client.player, isUsingThisHand || isChargedCrossbow, phys);
                breakdown.magicBonus = magic.damage > 0.0f ? magic.damage : magic.healing;

                float damageValue = applyFinalReductions(client, livingTarget, stack, phys, magic.damage, isProjectile, isExplosion, false, breakdown);
                if (magic.healing > 0.0f) {
                    float healthAfterPhysical = livingTarget.getHealth() - Math.max(0.0f, damageValue - livingTarget.getAbsorptionAmount());
                    float cappedHealing = Math.min(magic.healing, Math.max(0.0f, livingTarget.getMaxHealth() - healthAfterPhysical));
                    float netDamage = damageValue - cappedHealing;
                    if (netDamage < 0.0f) {
                        finalValue = -netDamage;
                        isHealing = true;
                        breakdown.healingAmount = finalValue;
                    } else {
                        finalValue = netDamage;
                    }
                    breakdown.finalDamage = finalValue;
                } else {
                    finalValue = damageValue;
                }
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

        // Armadillo rolled-up reduction (vanilla formula: (damage - 1) / 2)
        if (livingTarget instanceof ArmadilloEntity armadillo && !isHealing && overrideText == null) {
            if (armadillo.isRolledUp() && finalValue > 0.0f) {
                finalValue = Math.max(0.0f, (finalValue - 1.0f) * 0.5f);
            }
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

        // Log prediction before rendering
        if (!isHealing && finalValue > 0.0f) {
            DamageLogger.logPrediction(
                    client,
                    livingTarget,
                    stack,
                    finalValue,
                    (int) breakdown.armor,
                    (int) breakdown.toughness,
                    breakdown.resistanceLevel,
                    getLogProjectileCode(stack, isProjectile, isExplosion, isSplash, isLingering, isSnowball),
                    buildLogBonusFlags(isCrit, breakdown)
            );
        }

        String suffix = "";
        if (isCrit && !isProjectile && !isExplosion && !isSpear && !isSplash && !isLingering) {
            suffix = "^";
        } else if ((isUsingThisHand && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || isSpear)) || isChargedCrossbow) {
            suffix = "*";
        }
        if (ModConfig.showInaccuracyWarnings && (breakdown.cooldownWarning || breakdown.hurtWindowWarning)) {
            suffix = suffix + "~";
        }

        boolean noSmoothing = !ModConfig.smoothingEnabled || isSplash || isLingering || isCrit || isHealing || (isMain ? wasCritMain : wasCritOff);
        if (isMain) {
            wasCritMain = isCrit;
        } else {
            wasCritOff = isCrit;
        }

        int configuredColor = applyConfiguredColor(finalColor);

        if (isMain) {

            // If smash was just consumed, invalidate smoothing immediately
            if (SMASH_STATE.wasJustConsumed()) {
                resetMainSmoothingState();
            }

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

    private static int getLogProjectileCode(
            ItemStack stack,
            boolean isProjectile,
            boolean isExplosion,
            boolean isSplash,
            boolean isLingering,
            boolean isSnowball
    ) {
        if (isExplosion) return 3;
        if (isSplash) return 4;
        if (isLingering) return 5;
        if (isSnowball) return 6;
        if (stack.isOf(Items.TRIDENT) && isProjectile) return 2;
        return isProjectile ? 1 : 0;
    }

    private static String buildLogBonusFlags(boolean isCrit, DamageBreakdown breakdown) {
        StringBuilder flags = new StringBuilder();
        appendLogFlag(flags, "CR", isCrit);
        appendLogFlag(flags, "SM", breakdown.maceFallBonus > 0.0f);
        appendLogFlag(flags, "SP", breakdown.spearVelocityBonus > 0.0f);
        appendLogFlag(flags, "CW", breakdown.cooldownWarning);
        appendLogFlag(flags, "HW", breakdown.hurtWindowWarning);
        return flags.toString();
    }

    private static void appendLogFlag(StringBuilder flags, String flag, boolean enabled) {
        if (!enabled) return;
        if (!flags.isEmpty()) flags.append('|');
        flags.append(flag);
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
                        && isTargetableEntity(client, crosshairEntity)
                        && withinHorizontalLimit(camera, crosshairEntity, profile.horizontalLimit)
                        && hasClearLineOfSight(camera, crosshairEntity)) {
                    return crosshairEntity;
                }
            }

            Box scanBox = camera.getBoundingBox().expand(profile.horizontalLimit, profile.maxDistance, profile.horizontalLimit);
            return client.world.getOtherEntities(camera, scanBox, entity -> isTargetableEntity(client, entity) && withinHorizontalLimit(camera, entity, profile.horizontalLimit))
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
            if (isValidRayTarget(client, camera, crosshairEntity, start, end, firstBlockDistanceSquared, maxDistanceSquared)) {
                return crosshairEntity;
            }
        }

        EntityHitResult raycast = ProjectileUtil.raycast(
                camera,
                start,
                end,
                camera.getBoundingBox().stretch(rotation.multiply(profile.maxDistance)).expand(0.35d),
                entity -> isTargetableEntity(client, entity),
                firstBlockDistanceSquared
        );
        if (raycast != null && isValidRayTarget(client, camera, raycast.getEntity(), start, end, firstBlockDistanceSquared, maxDistanceSquared)) {
            return raycast.getEntity();
        }

        Box searchBox = camera.getBoundingBox().stretch(rotation.multiply(profile.maxDistance)).expand(0.35d);
        Entity best = null;
        double bestDistanceSquared = firstBlockDistanceSquared;
        for (Entity candidate : client.world.getOtherEntities(camera, searchBox, entity -> isTargetableEntity(client, entity))) {
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

    private static boolean isValidRayTarget(MinecraftClient client, Entity source, Entity target, Vec3d start, Vec3d end, double blockDistanceSquared, double maxDistanceSquared) {
        if (!isTargetableEntity(client, target)) return false;
        double hitDistanceSquared = getRayHitDistanceSquared(start, end, target);
        if (hitDistanceSquared < 0.0d) return false;
        if (hitDistanceSquared > maxDistanceSquared + 1.0E-5) return false;
        if (hitDistanceSquared > blockDistanceSquared + 1.0E-5) return false;
        return hasClearLineOfSight(source, target);
    }

    private static boolean isTargetableEntity(MinecraftClient client, Entity entity) {
        if (entity == null || !entity.canHit() || entity.isSpectator()) {
            return false;
        }
        return !(entity instanceof PlayerEntity player && isUntargetablePlayer(client, player));
    }

    private static boolean isUntargetablePlayer(MinecraftClient client, PlayerEntity player) {
        // Prefer network gamemode when available (authoritative)
        if (client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null && entry.getGameMode() != null) {
                GameMode gameMode = entry.getGameMode();
                if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) {
                    return true;
                }
                // Explicitly allow SURVIVAL / ADVENTURE even if local flags still desynced
                if (gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE) {
                    return false;
                }
            }
        }

        // Fallback to entity flags (can be briefly stale during transitions)
        return player.isCreative() || player.isSpectator();
    }

    private static double getRayHitDistanceSquared(Vec3d start, Vec3d end, Entity entity) {
        Box entityBox = entity.getBoundingBox().expand(entity.getTargetingMargin());
        if (entityBox.contains(start)) {
            return 0.0d;
        }
        var hit = entityBox.raycast(start, end);
        return hit.map(vec3d -> start.squaredDistanceTo(vec3d)).orElse(-1.0d);
    }

    private static Vec3d getTargetHitPos(MinecraftClient client, Entity target, ReachProfile profile) {
        Entity camera = client.getCameraEntity();
        if (camera == null || target == null) {
            return target != null ? target.getEntityPos() : Vec3d.ZERO;
        }

        Vec3d start = camera.getEyePos();
        Vec3d end = start.add(camera.getRotationVec(1.0f).multiply(profile.maxDistance));
        Box entityBox = target.getBoundingBox().expand(target.getTargetingMargin());
        return entityBox.raycast(start, end).orElse(target.getEntityPos());
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
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !player.hasStatusEffect(StatusEffects.SLOW_FALLING)
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
            float pull = getBowPullProgress(ticks);
            if (pull < 0.1f) return 0.0f;
            float damage = calculateArrowDamageFromVelocity(client, stack, pull * 3.0f, true, pull >= 1.0f, breakdown);
            breakdown.baseDamage = damage;
            breakdown.preMitigation = damage;
            return damage;
        }

        if (stack.isOf(Items.CROSSBOW)) {
            ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged == null || charged.isEmpty()) return 0.0f;

            ItemStack projectile = charged.getProjectiles().get(0);
            if (projectile.isOf(Items.FIREWORK_ROCKET)) {
                return 0.0f;
            }

            float damage = calculateArrowDamageFromVelocity(client, stack, 3.15f, false, true, breakdown);
            breakdown.baseDamage = damage;
            breakdown.preMitigation = damage;
            return damage;
        }

        float baseDamage = isSpear && isUsingThisHand
                ? (float) player.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE)
                : getWeaponBaseDamage(player, stack);
        float strengthBonus = isSpear && isUsingThisHand ? getStatusAttackDamageBonus(player) : getManualStatusAttackDamageBonus(player, stack);
        float weaknessPenalty = isSpear && isUsingThisHand ? getStatusAttackDamagePenalty(player) : getManualStatusAttackDamagePenalty(player, stack);

        breakdown.baseDamage = baseDamage;
        breakdown.strengthBonus = strengthBonus;
        breakdown.weaknessPenalty = weaknessPenalty;

        float total = baseDamage + strengthBonus + weaknessPenalty;

        if (stack.isOf(Items.MACE)) {

            float fallDistance = SMASH_STATE.getTrackedFallDistance();
            breakdown.maceFallDistance = fallDistance;

            if (SMASH_STATE.canApplySmash()) {
                if (SMASH_STATE.consumeIfSwinging(client)) {
                    breakdown.maceFallDistance = SMASH_STATE.getTrackedFallDistance();
                } else {
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

        return (float) calculatedFromStack[0];
    }

    private static float getBowPullProgress(int useTicks) {
        float pull = useTicks / 20.0F;
        pull = (pull * pull + pull * 2.0F) / 3.0F;
        return Math.min(pull, 1.0F);
    }

    private static float calculateArrowDamageFromVelocity(
            MinecraftClient client,
            ItemStack weapon,
            float launchSpeed,
            boolean includeShooterMovement,
            boolean critical,
            DamageBreakdown breakdown
    ) {
        PlayerEntity player = client.player;
        Vec3d velocity = player.getRotationVec(1.0f).normalize().multiply(launchSpeed);
        if (includeShooterMovement) {
            Vec3d movement = player.getMovement();
            velocity = velocity.add(movement.x, player.isOnGround() ? 0.0 : movement.y, movement.z);
        }

        float projectileDamage = 2.0f;
        float unenchantedProjectileDamage = projectileDamage;
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int power = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.POWER), weapon);
        if (power > 0) {
            projectileDamage += 0.5f * power + 0.5f;
            breakdown.enchantmentLevel = Math.max(breakdown.enchantmentLevel, power);
        }

        int baseHit = MathHelper.ceil(MathHelper.clamp((float) velocity.length() * projectileDamage, 0.0f, 2.14748365E9f));
        int unenchantedHit = MathHelper.ceil(MathHelper.clamp((float) velocity.length() * unenchantedProjectileDamage, 0.0f, 2.14748365E9f));
        float damage = baseHit;
        float unenchantedDamage = unenchantedHit;
        if (critical) {
            damage += getExpectedCriticalArrowBonus(baseHit);
            unenchantedDamage += getExpectedCriticalArrowBonus(unenchantedHit);
        }

        breakdown.enchantmentBonus += Math.max(0.0f, damage - unenchantedDamage);
        return damage;
    }

    private static float getExpectedCriticalArrowBonus(int baseDamage) {
        int bound = baseDamage / 2 + 2;
        return bound <= 1 ? 0.0f : (bound - 1) / 2.0f;
    }

    private static float getManualStatusAttackDamageBonus(PlayerEntity player, ItemStack stack) {
        return getStatusAttackDamageBonus(player);
    }

    private static float getManualStatusAttackDamagePenalty(PlayerEntity player, ItemStack stack) {
        return getStatusAttackDamagePenalty(player);
    }

    private static float getStatusAttackDamageBonus(PlayerEntity player) {
        int strengthLevel = getStatusEffectLevel(player, StatusEffects.STRENGTH);
        return strengthLevel > 0 ? 3.0f * strengthLevel : 0.0f;
    }

    private static float getStatusAttackDamagePenalty(PlayerEntity player) {
        int weaknessLevel = getStatusEffectLevel(player, StatusEffects.WEAKNESS);
        return weaknessLevel > 0 ? -4.0f * weaknessLevel : 0.0f;
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

        int breach = EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BREACH), stack);

        float armorInput = physicalDamage + enchantBonus;
        DamageProfile physicalProfile = isExplosion ? DamageProfile.EXPLOSION : (isProjectile ? DamageProfile.PROJECTILE : DamageProfile.MELEE);
        ReductionResult physical = reduceDamage(client, target, armorInput, physicalProfile, breach);
        ReductionResult magic = magicDamage > 0.0f
                ? reduceDamage(client, target, magicDamage, DamageProfile.MAGIC, 0)
                : ReductionResult.empty();

        breakdown.initialOutput = armorInput + magicDamage;
        breakdown.armor = physical.armor;
        breakdown.toughness = physical.toughness;
        breakdown.armorInput = armorInput;
        breakdown.afterArmor = physical.afterArmor + magic.afterArmor;
        breakdown.armorPenalty = physical.armorPenalty + magic.armorPenalty;
        breakdown.armorPenaltyPercent = armorInput > 0.0f ? (physical.armorPenalty / armorInput) * 100.0f : 0.0f;
        breakdown.resistanceLevel = Math.max(physical.resistanceLevel, magic.resistanceLevel);
        breakdown.resistanceInferredFromParticles = physical.resistanceInferredFromParticles || magic.resistanceInferredFromParticles;
        breakdown.resistanceMultiplier = Math.min(physical.resistanceMultiplier, magic.resistanceMultiplier);
        breakdown.resistanceInput = physical.resistanceInput + magic.resistanceInput;
        breakdown.resistancePenalty = physical.resistancePenalty + magic.resistancePenalty;
        breakdown.resistancePenaltyPercent = breakdown.resistanceInput > 0.0f
                ? (breakdown.resistancePenalty / breakdown.resistanceInput) * 100.0f
                : 0.0f;
        breakdown.epf = Math.max(physical.epf, magic.epf);
        breakdown.protectionMultiplier = Math.min(physical.protectionMultiplier, magic.protectionMultiplier);
        breakdown.protectionPenalty = physical.protectionPenalty + magic.protectionPenalty;
        float protectionInput = physical.afterResistance + magic.afterResistance;
        breakdown.protectionPenaltyPercent = protectionInput > 0.0f
                ? (breakdown.protectionPenalty / protectionInput) * 100.0f
                : 0.0f;

        float finalDamage = physical.finalDamage + magic.finalDamage;
        breakdown.finalDamage = finalDamage;
        return finalDamage;
    }

    private static ReductionResult reduceDamage(
            MinecraftClient client,
            LivingEntity target,
            float damage,
            DamageProfile profile,
            int breachLevel
    ) {
        if (damage <= 0.0f) {
            return ReductionResult.empty();
        }

        ReductionResult result = new ReductionResult();
        result.armor = (float) target.getAttributeValue(EntityAttributes.ARMOR);
        result.toughness = (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        result.armorInput = damage;

        if (profile.bypassesArmor) {
            result.afterArmor = damage;
        } else {
            float armorEffectiveness = calculateArmorEffectiveness(damage, result.armor, result.toughness);
            if (breachLevel > 0) {
                armorEffectiveness = MathHelper.clamp(armorEffectiveness - breachLevel * 0.15f, 0.0f, 1.0f);
            }
            result.afterArmor = damage * (1.0f - armorEffectiveness);
        }
        result.armorPenalty = Math.max(0.0f, damage - result.afterArmor);

        if (profile == DamageProfile.MAGIC && target instanceof WitchEntity) {
            float beforeWitchResistance = result.afterArmor;
            result.afterArmor *= 0.15f;
            result.armorPenalty += Math.max(0.0f, beforeWitchResistance - result.afterArmor);
        }

        result.resistanceLevel = ResistanceStateManager.resolveResistanceLevel(client, target);
        result.resistanceInferredFromParticles =
                result.resistanceLevel > 0
                && !target.hasStatusEffect(StatusEffects.RESISTANCE);
        result.resistanceMultiplier = result.resistanceLevel > 0
                ? Math.max(0.0f, 1.0f - result.resistanceLevel * 0.2f)
                : 1.0f;
        result.resistanceInput = result.afterArmor;
        result.afterResistance = result.resistanceInput * result.resistanceMultiplier;
        result.resistancePenalty = Math.max(0.0f, result.resistanceInput - result.afterResistance);

        result.epf = getProtectionAmount(client, target, profile);
        result.protectionMultiplier = 1.0f - (Math.min(20.0f, (float) result.epf) * 0.04f);
        result.finalDamage = result.afterResistance * result.protectionMultiplier;
        result.protectionPenalty = Math.max(0.0f, result.afterResistance - result.finalDamage);
        return result;
    }

    private static float calculateArmorEffectiveness(float damage, float armor, float toughness) {
        float toughnessScale = 2.0f + toughness / 4.0f;
        float armorAfterHit = MathHelper.clamp(armor - damage / toughnessScale, armor * 0.2f, 20.0f);
        return armorAfterHit / 25.0f;
    }

    private static int getProtectionAmount(MinecraftClient client, LivingEntity target, DamageProfile profile) {
        int epf = 0;
        var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armorPiece = target.getEquippedStack(slot);
            if (armorPiece.isEmpty()) continue;

            epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROTECTION), armorPiece);
            if (profile == DamageProfile.PROJECTILE) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.PROJECTILE_PROTECTION), armorPiece) * 2;
            } else if (profile == DamageProfile.EXPLOSION) {
                epf += EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.BLAST_PROTECTION), armorPiece) * 2;
            }
        }
        return epf;
    }

    private static PotionResult calculatePotionResult(MinecraftClient client, LivingEntity target, ItemStack stack, boolean lingering) {
        PotionResult result = new PotionResult();
        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return result;

        boolean undead = target.hasInvertedHealingAndHarm();
        boolean immuneToPoison = undead
                || target instanceof EnderDragonEntity
                || target instanceof WitherEntity
                || target.getType().equals(EntityType.SPIDER)
                || target.getType().equals(EntityType.CAVE_SPIDER)
                || target.getType().equals(EntityType.BOGGED);
        var effects = contents.getEffects();

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
                    result.color = colorOrDefault(ModConfig.healingColor, 0xFF00FF00);
                }
                continue;
            }
            if (lingering) {
                if (isHeal) {
                    result.overrideText = undead ? "-" + heartText : "+" + heartText;
                    result.color = undead ? colorOrDefault(ModConfig.damagePotionColor, 0xFF990000) : colorOrDefault(ModConfig.healingColor, 0xFF00FF00);
                } else if (isHarm) {
                    result.overrideText = undead ? "+" + heartText : "-" + heartText;
                    result.color = undead ? colorOrDefault(ModConfig.healingColor, 0xFF00FF00) : colorOrDefault(ModConfig.damagePotionColor, 0xFF990000);
                }
                continue;
            }

            if (isHeal) {
                float value = undead ? getInstantDamageAmount(effect.getAmplifier()) : getInstantHealAmount(effect.getAmplifier());
                result.healing = !undead;
                result.value = result.healing ? value : reduceDamage(client, target, value, DamageProfile.MAGIC, 0).finalDamage;
            } else if (isHarm) {
                float value = undead ? getInstantHealAmount(effect.getAmplifier()) : getInstantDamageAmount(effect.getAmplifier());
                result.healing = undead;
                result.value = result.healing ? value : reduceDamage(client, target, value, DamageProfile.MAGIC, 0).finalDamage;
            }
        }
        return result;
    }

    private static float calculateFireworkExplosionDamage(MinecraftClient client, LivingEntity target, ItemStack crossbow, Vec3d explosionPos) {
        ChargedProjectilesComponent charged = crossbow.get(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged == null || charged.isEmpty()) return 0.0f;

        ItemStack projectile = charged.getProjectiles().get(0);
        if (!projectile.isOf(Items.FIREWORK_ROCKET)) return 0.0f;

        FireworksComponent fireworks = projectile.get(DataComponentTypes.FIREWORKS);
        if (fireworks == null || fireworks.explosions().isEmpty()) return 0.0f;
        if (!hasFireworkExposure(client, target, explosionPos)) return 0.0f;

        float baseDamage = 5.0f + fireworks.explosions().size() * 2.0f;
        double distance = explosionPos.distanceTo(target.getEntityPos());
        if (distance > 5.0) return 0.0f;
        return baseDamage * MathHelper.sqrt((float) ((5.0 - distance) / 5.0));
    }

    private static boolean hasFireworkExposure(MinecraftClient client, LivingEntity target, Vec3d explosionPos) {
        if (client.world == null || client.player == null) return false;
        for (int i = 0; i < 2; i++) {
            Vec3d targetPos = new Vec3d(target.getX(), target.getBodyY(0.5 * i), target.getZ());
            HitResult hit = client.world.raycast(new RaycastContext(
                    explosionPos,
                    targetPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    client.player
            ));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeflectedProjectileTarget(LivingEntity target, boolean isDeflectableProjectile) {
        if (!isDeflectableProjectile) return false;
        if (target.getType().isIn(EntityTypeTags.DEFLECTS_PROJECTILES)) {
            return true;
        }
        return false;
    }

    private static int getStatusEffectLevel(LivingEntity target, RegistryEntry<StatusEffect> effect) {
        StatusEffectInstance direct = target.getStatusEffect(effect);
        if (direct != null) {
            return direct.getAmplifier() + 1;
        }
        for (StatusEffectInstance instance : target.getStatusEffects()) {
            if (instance.getEffectType().matches(effect)) {
                return instance.getAmplifier() + 1;
            }
        }
        return 0;
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

    private static MagicEffectResult calculateArrowInstantEffect(LivingEntity target, ItemStack weapon, PlayerEntity player, boolean active, float normalArrowDamage) {
        MagicEffectResult result = new MagicEffectResult();
        if (!active) return result;

        ItemStack projectile = weapon.isOf(Items.BOW)
                ? player.getProjectileType(weapon)
                : (weapon.get(DataComponentTypes.CHARGED_PROJECTILES) != null && !weapon.get(DataComponentTypes.CHARGED_PROJECTILES).isEmpty()
                ? weapon.get(DataComponentTypes.CHARGED_PROJECTILES).getProjectiles().get(0)
                : ItemStack.EMPTY);
        if (projectile.isEmpty() || !projectile.contains(DataComponentTypes.POTION_CONTENTS)) return result;

        boolean inverted = target.hasInvertedHealingAndHarm();
        for (StatusEffectInstance effect : projectile.get(DataComponentTypes.POTION_CONTENTS).getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) {
                if (inverted) {
                    result.damage += getInstantArrowExtraDamage(effect, normalArrowDamage);
                } else {
                    result.healing += getInstantHealAmount(effect.getAmplifier());
                }
            } else if (effect.getEffectType().equals(StatusEffects.INSTANT_DAMAGE)) {
                if (inverted) {
                    result.healing += getInstantHealAmount(effect.getAmplifier());
                } else {
                    result.damage += getInstantArrowExtraDamage(effect, normalArrowDamage);
                }
            }
        }
        return result;
    }

    private static float getInstantArrowExtraDamage(StatusEffectInstance effect, float normalArrowDamage) {
        return Math.max(0.0f, getInstantDamageAmount(effect.getAmplifier()) - normalArrowDamage);
    }

    private static float getInstantHealAmount(int amplifier) {
        return Math.max(4 << amplifier, 0);
    }

    private static float getInstantDamageAmount(int amplifier) {
        return Math.max(6 << amplifier, 0);
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
        if (healing) return colorOrDefault(ModConfig.healingColor, 0xFF00FF00);
        float health = target.getHealth() + target.getAbsorptionAmount();
        if (stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) {
            return colorOrDefault(ModConfig.damagePotionColor, 0xFF990000);
        }
        return damage >= health
                ? (isEnemyHoldingTotem(target)
                ? colorOrDefault(ModConfig.totemDamageColor, 0xFFF2FF00)
                : colorOrDefault(ModConfig.lethalDamageColor, 0xFFFF0000))
                : 0xFFFFFFFF;
    }

    private static int colorOrDefault(java.awt.Color color, int defaultColor) {
        return color != null ? color.getRGB() : defaultColor;
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
        int x = ModConfig.debugX;
        int y = ModConfig.debugY;
        int neutralColor = colorOrDefault(ModConfig.debugNeutralColor, 0xFFE6E6E6);
        int inputOutputColor = colorOrDefault(ModConfig.debugInputOutputColor, 0xFF8FFF48);
        int finalOutputColor = colorOrDefault(ModConfig.debugFinalOutputColor, 0xFFFFA640);
        int warningColor = colorOrDefault(ModConfig.debugWarningColor, 0xFFFFC04A);
        int immuneDebugColor = applyConfiguredColor(IMMUNE_COLOR);
        float strWeakBonus = breakdown.strengthBonus + breakdown.weaknessPenalty;
        String spearRelativeSpeed = isSpear ? String.format(Locale.ROOT, "%.2fm/s", breakdown.spearRelativeSpeed) : "n/a";
        String healingAmount = isHealing ? String.format(Locale.ROOT, "%.2f", breakdown.healingAmount) : "n/a";

        drawDebugLine(ctx, client, x, y, "B4's Pre-Damage Indicator Debug Mode [F7]", 0xFFFFFFFF);
        y += 10;
        y = drawDebugLineIf(ctx, client, x, y, "Item: " + stack.getName().getString(), neutralColor, ModConfig.debugShowItem);
        String targetType = target == null ? "none" : String.valueOf(Registries.ENTITY_TYPE.getId(target.getType()));
        y = drawDebugLineIf(ctx, client, x, y, "Target: " + targetType, neutralColor, ModConfig.debugShowTarget);
        if (target instanceof LivingEntity livingTarget) {
            y = drawDebugLineIf(
                    ctx,
                    client,
                    x,
                    y,
                    String.format(Locale.ROOT, "Target HP: %.1f/%.1f", livingTarget.getHealth() + livingTarget.getAbsorptionAmount(), livingTarget.getMaxHealth()),
                    neutralColor,
                    ModConfig.debugShowHealth
            );
        } else {
            y = drawDebugLineIf(ctx, client, x, y, "Target HP: n/a", neutralColor, ModConfig.debugShowHealth);
        }
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Initial input: %.2f  str/weak bonus: %s", breakdown.baseDamage, formatSignedValue(strWeakBonus)), neutralColor, ModConfig.debugShowInput);
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Crit: %s (x%.1f)  Bonus: %s", isCrit, breakdown.critMultiplier, formatSignedValue(breakdown.critBonus)), neutralColor, ModConfig.debugShowCrit);
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Ench lvl: %d  Bonus: %s", breakdown.enchantmentLevel, formatSignedValue(breakdown.enchantmentBonus)), neutralColor, ModConfig.debugShowEnchantments);
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Magic bonus: %s", formatSignedValue(breakdown.magicBonus)), neutralColor, ModConfig.debugShowMagic);
        y = drawDebugLineIf(
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
                neutralColor,
                ModConfig.debugShowSpear
        );
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Is Mace: %s  fallDist: %.2f  Bonus: %s", stack.isOf(Items.MACE), breakdown.maceFallDistance, formatSignedValue(breakdown.maceFallBonus)), neutralColor, ModConfig.debugShowMace);
        y = drawDebugLineIf(ctx, client, x, y, "Is offhand: " + !isMain + "  type: " + damageType + "  Is using: " + isUsingThisHand, neutralColor, ModConfig.debugShowState);
        y = drawDebugLineIf(ctx, client, x, y, String.format(Locale.ROOT, "Final Input/Initial Output: %.2f", breakdown.initialOutput), inputOutputColor, ModConfig.debugShowReductions);
        y = drawDebugLineIf(
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
                neutralColor,
                ModConfig.debugShowReductions
        );
        y = drawDebugLineIf(
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
                ) + (breakdown.resistanceInferredFromParticles ? " (particle)" : ""),
                neutralColor,
                ModConfig.debugShowReductions
        );
        y = drawDebugLineIf(
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
                neutralColor,
                ModConfig.debugShowReductions
        );
        if (ModConfig.debugShowFinal) {
            drawDebugSegments(
                    ctx,
                    client,
                    x,
                    y,
                    new DebugSegment(String.format(Locale.ROOT, "Final Output: %.2f  ", breakdown.finalDamage), finalOutputColor),
                    new DebugSegment("Is immune: " + breakdown.immune, immuneDebugColor)
            );
            y += 10;
        }
        y = drawDebugLineIf(ctx, client, x, y, "Is Healing: " + isHealing + "  Amount: " + healingAmount, neutralColor, ModConfig.debugShowMagic);
        drawDebugLineIf(ctx, client, x, y, "Cooldown: " + breakdown.cooldownWarning + "  HurtWindow: " + breakdown.hurtWindowWarning, warningColor, ModConfig.debugShowWarnings);
    }

    private static int drawDebugLineIf(DrawContext ctx, MinecraftClient client, int x, int y, String text, int color, boolean show) {
        if (show) {
            drawDebugLine(ctx, client, x, y, text, color);
            return y + 10;
        }
        return y;
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

    private static void expireOrKeepMainSmoothingState(ItemStack stack) {
        if (stack.isOf(Items.MACE)) {
            resetMainSmoothingState();
            return;
        }
        expireOrKeepMainSmoothingState();
    }

    private record ReachProfile(float maxDistance, boolean maceVerticalOnly, float horizontalLimit) {
    }

    private record DebugSegment(String text, int color) {
    }

    private enum DamageProfile {
        MELEE(false),
        PROJECTILE(false),
        EXPLOSION(false),
        MAGIC(true);

        private final boolean bypassesArmor;

        DamageProfile(boolean bypassesArmor) {
            this.bypassesArmor = bypassesArmor;
        }
    }

    private static class ReductionResult {
        float armor;
        float toughness;
        float armorInput;
        float afterArmor;
        float armorPenalty;
        int resistanceLevel;
        boolean resistanceInferredFromParticles;
        float resistanceMultiplier = 1.0f;
        float resistanceInput;
        float afterResistance;
        float resistancePenalty;
        int epf;
        float protectionMultiplier = 1.0f;
        float protectionPenalty;
        float finalDamage;

        static ReductionResult empty() {
            return new ReductionResult();
        }
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
        boolean resistanceInferredFromParticles;
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

    private static class MagicEffectResult {
        float damage = 0.0f;
        float healing = 0.0f;
    }
}
