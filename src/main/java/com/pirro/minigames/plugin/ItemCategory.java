package com.pirro.minigames.plugin;

/**
 * The buckets random items are grouped into for weighted selection. Every
 * permitted item auto-classifies into exactly one of these (see
 * ItemClassifier) except JACKPOT, which is never auto-assigned - it only
 * exists so the jackpot roll chance can be configured alongside the other
 * category weights in random-items.lucky-weights.
 */
enum ItemCategory {
    BLOCKS("blocks", false),
    FOOD("food", false),
    PROJECTILES("projectiles", false),
    UTILITY("utility", false),
    TOOLS("tools", true),
    WEAPONS("weapons", true),
    ARMOR("armor", true),
    CHAOS("chaos", false),
    JACKPOT("jackpot", false);

    private final String configKey;
    private final boolean equipment;

    ItemCategory(String configKey, boolean equipment) {
        this.configKey = configKey;
        this.equipment = equipment;
    }

    /** The key this category's weight is read from under random-items.*-weights. */
    String configKey() {
        return configKey;
    }

    /**
     * True for tools/weapons/armor, whose rewards trigger the lucky-block
     * equipment cooldown. Jackpot rewards are equipment-flagged individually
     * (see JackpotTable.Entry) since the jackpot table mixes equipment and
     * consumable items under one category.
     */
    boolean isEquipment() {
        return equipment;
    }
}
