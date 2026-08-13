package com.pirro.minigames.plugin;

import org.bukkit.inventory.ItemType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The curated, weighted jackpot reward table rolled from the lucky-block JACKPOT category. */
final class JackpotTable {
    private final List<Entry> entries;
    private final List<Entry> nonEquipmentEntries;
    private final int totalWeight;
    private final int nonEquipmentTotalWeight;

    JackpotTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        this.nonEquipmentEntries = this.entries.stream().filter(entry -> !entry.equipment()).toList();
        this.totalWeight = this.entries.stream().mapToInt(Entry::weight).sum();
        this.nonEquipmentTotalWeight = this.nonEquipmentEntries.stream().mapToInt(Entry::weight).sum();
    }

    boolean isEmpty() {
        return totalWeight <= 0;
    }

    int totalWeight(boolean equipmentAllowed) {
        return equipmentAllowed ? totalWeight : nonEquipmentTotalWeight;
    }

    /** Callers must only pass equipmentAllowed=false when totalWeight(false) > 0. */
    Entry roll(boolean equipmentAllowed) {
        List<Entry> pool = equipmentAllowed ? entries : nonEquipmentEntries;
        int total = equipmentAllowed ? totalWeight : nonEquipmentTotalWeight;
        if (total <= 0) {
            pool = entries;
            total = totalWeight;
        }

        int roll = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (Entry entry : pool) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        throw new IllegalStateException("unreachable: jackpot roll exhausted without a selection");
    }

    record Entry(ItemType itemType, int weight, int amount, boolean equipment) {
    }
}
