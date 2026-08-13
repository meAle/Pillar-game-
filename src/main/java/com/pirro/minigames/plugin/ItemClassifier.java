package com.pirro.minigames.plugin;

import org.bukkit.inventory.ItemType;

import java.util.Set;

/**
 * Assigns every permitted Minecraft item to exactly one ItemCategory. Uses
 * material-shape signals (name suffixes, Bukkit's isEdible()/hasBlockType())
 * for the common case, plus a short curated list for items whose vanilla
 * "personality" doesn't match their raw material shape - TNT is a block but
 * plays like a wildcard, spawn eggs aren't blocks or tools or anything else,
 * horse armor can't be worn by a player, and so on. See the checks below for
 * the exact precedence; the first match wins.
 */
final class ItemClassifier {
    private static final Set<String> CHAOS_KEYS = Set.of(
            "tnt", "tnt_minecart", "fire_charge", "firework_rocket", "end_crystal", "lava_bucket",
            "leather_horse_armor", "iron_horse_armor", "golden_horse_armor", "diamond_horse_armor"
    );
    private static final Set<String> WEAPON_KEYS = Set.of("bow", "crossbow", "trident", "mace", "shield");
    private static final Set<String> TOOL_KEYS = Set.of(
            "fishing_rod", "flint_and_steel", "shears", "brush", "carrot_on_a_stick", "warped_fungus_on_a_stick"
    );
    private static final Set<String> PROJECTILE_KEYS = Set.of(
            "arrow", "spectral_arrow", "tipped_arrow", "snowball", "egg", "experience_bottle",
            "splash_potion", "lingering_potion", "wind_charge"
    );

    private ItemClassifier() {
    }

    static ItemCategory classify(ItemType item) {
        String key = item.getKey().getKey();

        // Wildcards first: these would otherwise be swallowed by isBlock()/tool-suffix
        // checks below, but their gameplay role is "unpredictable", not "building material".
        if (key.endsWith("_spawn_egg") || CHAOS_KEYS.contains(key)) {
            return ItemCategory.CHAOS;
        }
        if (key.endsWith("_helmet") || key.endsWith("_chestplate")
                || key.endsWith("_leggings") || key.endsWith("_boots") || key.equals("elytra")) {
            return ItemCategory.ARMOR;
        }
        // Axes are combat-capable but classified as TOOLS below, matching vanilla's own
        // creative-menu grouping (axes sit in the Tools tab; swords/bow/crossbow/trident/
        // mace/shield sit in Combat) - the one deliberately-vanilla-matching tie-break here.
        if (key.endsWith("_sword") || WEAPON_KEYS.contains(key)) {
            return ItemCategory.WEAPONS;
        }
        if (key.endsWith("_pickaxe") || key.endsWith("_axe") || key.endsWith("_shovel") || key.endsWith("_hoe")
                || TOOL_KEYS.contains(key)) {
            return ItemCategory.TOOLS;
        }
        if (PROJECTILE_KEYS.contains(key)) {
            return ItemCategory.PROJECTILES;
        }
        if (item.isEdible()) {
            return ItemCategory.FOOD;
        }
        if (item.hasBlockType()) {
            return ItemCategory.BLOCKS;
        }
        // Catch-all: potions, buckets, maps, books, spyglass, bundles, name tags, etc.
        return ItemCategory.UTILITY;
    }
}
