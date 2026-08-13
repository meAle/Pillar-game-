package com.pirro.minigames.plugin;

import org.bukkit.inventory.ItemType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * A category-weighted item picker: rolls a category by weight, then an item
 * uniformly within that category's pool. Categories with zero weight or an
 * empty pool are silently skipped rather than treated as errors (see
 * RandomItemPool for the "the whole profile is empty" startup check, which
 * belongs to the caller since a lucky profile can still be viable on jackpot
 * alone even if every category here is empty).
 */
final class WeightedLootProfile {
    private final Map<ItemCategory, Integer> weights;
    private final Map<ItemCategory, List<ItemType>> pools;

    WeightedLootProfile(Map<ItemCategory, Integer> weights, Map<ItemCategory, List<ItemType>> pools) {
        this.weights = new EnumMap<>(weights);
        this.pools = new EnumMap<>(ItemCategory.class);
        for (Map.Entry<ItemCategory, Integer> entry : this.weights.entrySet()) {
            this.pools.put(entry.getKey(), pools.getOrDefault(entry.getKey(), List.of()));
        }
    }

    /** Sum of weights for categories that are selectable (weight>0, non-empty pool) and pass filter. */
    int totalEligibleWeight(Predicate<ItemCategory> filter) {
        int total = 0;
        for (Map.Entry<ItemCategory, Integer> entry : weights.entrySet()) {
            if (isEligible(entry.getKey(), entry.getValue()) && filter.test(entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /**
     * Rolls a category among those passing filter, then an item uniformly within it.
     * Falls back to an unfiltered roll if the filter excludes everything eligible,
     * rather than ever crash mid-match - callers that must not do that (e.g. an
     * empty profile) are expected to validate with totalEligibleWeight at startup.
     */
    ItemType roll(Predicate<ItemCategory> filter) {
        int total = totalEligibleWeight(filter);
        if (total <= 0) {
            filter = category -> true;
            total = totalEligibleWeight(filter);
        }
        if (total <= 0) {
            throw new IllegalStateException("this loot profile has no selectable items");
        }

        int roll = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (Map.Entry<ItemCategory, Integer> entry : weights.entrySet()) {
            ItemCategory category = entry.getKey();
            int weight = entry.getValue();
            if (!isEligible(category, weight) || !filter.test(category)) {
                continue;
            }
            cumulative += weight;
            if (roll < cumulative) {
                List<ItemType> pool = pools.get(category);
                return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            }
        }
        throw new IllegalStateException("unreachable: weighted roll exhausted without a selection");
    }

    private boolean isEligible(ItemCategory category, Integer weight) {
        return weight != null && weight > 0 && !pools.getOrDefault(category, List.of()).isEmpty();
    }
}
