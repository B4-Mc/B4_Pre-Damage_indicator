package com.b4.predamage.client;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * Compact enchantment encoder for 1.21+
 *
 * Uses ItemEnchantmentsComponent (component system).
 */
public class EnchantmentEncoder {

    public static String encode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";

        var component = stack.getEnchantments();
        if (component == null || component.isEmpty()) return "";

        var enchantments = component.getEnchantments();
        if (enchantments == null || enchantments.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        for (RegistryEntry<Enchantment> entry : enchantments) {
            String id = entry.getIdAsString();
            int level = component.getLevel(entry);

            String shortCode = shorten(id);
            if (shortCode.isEmpty()) continue;

            if (!sb.isEmpty()) sb.append("|");
            sb.append(shortCode).append(level);
        }

        return sb.toString();
    }

    private static String shorten(String fullId) {
        if (fullId.contains("sharpness")) return "SH";
        if (fullId.contains("smite")) return "SM";
        if (fullId.contains("bane_of_arthropods")) return "BA";
        if (fullId.contains("power")) return "PO";
        if (fullId.contains("efficiency")) return "EF";
        if (fullId.contains("unbreaking")) return "UB";
        if (fullId.contains("protection")) return "PR";
        if (fullId.contains("feather_falling")) return "FF";
        if (fullId.contains("thorns")) return "TH";

        return "";
    }
}