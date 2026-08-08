package com.pirro.minigames.plugin;

import io.papermc.paper.entity.PlayerGiveResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The pool of random items/blocks handed out during rounds.
 */
final class RandomItemPool {
    private final List<ItemType> items;

    RandomItemPool(World world, Set<String> exclusions) {
        this.items = Registry.ITEM.stream()
                .filter(item -> !item.getKey().getKey().equals("air"))
                .filter(world::isEnabled)
                .filter(item -> !exclusions.contains(item.getKey().toString()))
                .toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("random item pool is empty; check random-items.excluded");
        }
    }

    int size() {
        return items.size();
    }

    void give(Player player, String label) {
        ItemType selected = items.get(ThreadLocalRandom.current().nextInt(items.size()));
        PlayerGiveResult result = player.give(selected.createItemStack(1));
        player.sendActionBar(Component.text(label + ": ", NamedTextColor.GREEN)
                .append(Component.translatable(selected.translationKey()).color(NamedTextColor.GOLD)));
        if (!result.drops().isEmpty()) {
            player.sendActionBar(Component.text("Your inventory was full, so the random item was dropped at your feet.",
                    NamedTextColor.RED));
        }
    }

    void drop(Location location) {
        ItemType selected = items.get(ThreadLocalRandom.current().nextInt(items.size()));
        location.getWorld().dropItemNaturally(location, selected.createItemStack(1));
    }

    void giveLucky(Player player, String label) {
        ItemType selected = randomItem();
        PlayerGiveResult result = player.give(createLuckyStack(selected));
        player.sendActionBar(Component.text(label + ": ", NamedTextColor.GREEN)
                .append(Component.translatable(selected.translationKey()).color(NamedTextColor.GOLD)));
        if (!result.drops().isEmpty()) {
            player.sendActionBar(Component.text("Your inventory was full, so the lucky item was dropped at your feet.",
                    NamedTextColor.RED));
        }
    }

    void dropLucky(Location location) {
        ItemType selected = randomItem();
        location.getWorld().dropItemNaturally(location, createLuckyStack(selected));
    }

    private ItemType randomItem() {
        return items.get(ThreadLocalRandom.current().nextInt(items.size()));
    }

    private ItemStack createLuckyStack(ItemType selected) {
        ItemStack stack = selected.createItemStack(1);
        applyBestEnchantment(stack);
        return stack;
    }

    private static void applyBestEnchantment(ItemStack stack) {
        String itemName = stack.getType().getKey().getKey();
        Enchantment enchantment = null;

        if (itemName.endsWith("_sword")) {
            enchantment = Enchantment.SHARPNESS;
        } else if (itemName.endsWith("_pickaxe")
                || itemName.endsWith("_axe")
                || itemName.endsWith("_shovel")
                || itemName.endsWith("_hoe")) {
            enchantment = Enchantment.EFFICIENCY;
        } else if (itemName.endsWith("_helmet")
                || itemName.endsWith("_chestplate")
                || itemName.endsWith("_leggings")
                || itemName.endsWith("_boots")) {
            enchantment = Enchantment.PROTECTION;
        } else if (stack.getType() == org.bukkit.Material.BOW) {
            enchantment = Enchantment.POWER;
        } else if (stack.getType() == org.bukkit.Material.CROSSBOW) {
            enchantment = Enchantment.QUICK_CHARGE;
        } else if (stack.getType() == org.bukkit.Material.TRIDENT) {
            enchantment = Enchantment.IMPALING;
        } else if (stack.getType() == org.bukkit.Material.MACE) {
            enchantment = Enchantment.DENSITY;
        } else if (stack.getType() == org.bukkit.Material.SHIELD
                || stack.getType() == org.bukkit.Material.ELYTRA) {
            enchantment = Enchantment.UNBREAKING;
        }

        if (enchantment != null) {
            stack.addUnsafeEnchantment(enchantment, enchantment.getMaxLevel());
        }
        if (stack.getType().getMaxDurability() > 0) {
            stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
    }
}
