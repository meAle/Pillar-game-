package com.pirro.minigames.plugin;

import org.bukkit.inventory.ItemType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player lucky-block memory: the last five items rolled (so the same
 * exact item can't repeat immediately) and a cooldown that blocks another
 * equipment-category reward for 15 breaks after receiving one.
 */
final class LuckyRollHistory {
    private static final int HISTORY_SIZE = 5;
    private static final int EQUIPMENT_COOLDOWN_BREAKS = 15;

    private final Map<UUID, Deque<ItemType>> recentItems = new HashMap<>();
    private final Map<UUID, Integer> equipmentCooldowns = new HashMap<>();

    boolean isRecent(UUID playerId, ItemType item) {
        Deque<ItemType> recent = recentItems.get(playerId);
        return recent != null && recent.contains(item);
    }

    boolean isInEquipmentCooldown(UUID playerId) {
        return equipmentCooldowns.getOrDefault(playerId, 0) > 0;
    }

    /** Records the outcome of one lucky-block break: pushes history and advances the cooldown. */
    void record(UUID playerId, ItemType item, boolean equipment) {
        Deque<ItemType> recent = recentItems.computeIfAbsent(playerId, ignored -> new ArrayDeque<>(HISTORY_SIZE));
        recent.addLast(item);
        while (recent.size() > HISTORY_SIZE) {
            recent.removeFirst();
        }

        if (equipment) {
            equipmentCooldowns.put(playerId, EQUIPMENT_COOLDOWN_BREAKS);
        } else {
            equipmentCooldowns.computeIfPresent(playerId, (id, remaining) -> Math.max(0, remaining - 1));
        }
    }

    void reset() {
        recentItems.clear();
        equipmentCooldowns.clear();
    }
}
