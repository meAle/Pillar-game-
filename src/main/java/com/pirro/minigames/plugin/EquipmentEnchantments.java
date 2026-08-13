package com.pirro.minigames.plugin;

import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Applies every useful, mutually compatible enchantment at its maximum level. */
final class EquipmentEnchantments {
    private static final String EXCLUDED_ENCHANTMENT = "knockback";
    private static final Map<String, Integer> PREFERENCE = Map.ofEntries(
            Map.entry("protection", 0),
            Map.entry("sharpness", 0),
            Map.entry("fortune", 0),
            Map.entry("infinity", 0),
            Map.entry("loyalty", 0),
            Map.entry("density", 0),
            Map.entry("multishot", 0),
            Map.entry("mending", 10)
    );

    private EquipmentEnchantments() {
    }

    static void applyMaximumCompatibleEnchantments(ItemStack stack) {
        List<Enchantment> applicable = Registry.ENCHANTMENT.stream()
                .filter(enchantment -> !enchantment.isCursed())
                .filter(enchantment -> !enchantment.getKey().getKey().equals(EXCLUDED_ENCHANTMENT))
                .filter(enchantment -> enchantment.canEnchantItem(stack))
                .sorted(Comparator
                        .comparingInt(EquipmentEnchantments::preference)
                        .thenComparing(enchantment -> enchantment.getKey().getKey()))
                .toList();

        for (Enchantment enchantment : applicable) {
            boolean conflicts = stack.getEnchantments().keySet().stream()
                    .anyMatch(existing -> existing.conflictsWith(enchantment));
            if (!conflicts) {
                stack.addUnsafeEnchantment(enchantment, enchantment.getMaxLevel());
            }
        }
    }

    private static int preference(Enchantment enchantment) {
        return PREFERENCE.getOrDefault(enchantment.getKey().getKey(), 5);
    }
}
