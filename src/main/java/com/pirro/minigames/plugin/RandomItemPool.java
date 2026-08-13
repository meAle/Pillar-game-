package com.pirro.minigames.plugin;

import io.papermc.paper.entity.PlayerGiveResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Owns two weighted loot profiles built once at startup from the permitted
 * item registry: a "timed" profile for phase rewards and a "lucky" profile
 * (categories + a curated jackpot table) for lucky-block rewards. Also owns
 * the lucky-block per-player reroll/cooldown history (see LuckyRollHistory).
 */
final class RandomItemPool {
    private static final int MAX_RECENCY_REROLLS = 25;

    private final WeightedLootProfile timedProfile;
    private final WeightedLootProfile luckyProfile;
    private final JackpotTable jackpotTable;
    private final int luckyJackpotWeight;
    private final LuckyRollHistory luckyHistory = new LuckyRollHistory();
    private final int size;

    RandomItemPool(World world, GameSettings settings) {
        Set<String> exclusions = settings.excludedItemKeys();

        // Resolve the jackpot table first so its items can be carved out of the
        // auto-classified pools below - jackpot rewards are exclusive to the jackpot roll,
        // not duplicated into their "natural" category (e.g. netherite_sword isn't also a
        // possible plain WEAPONS pick).
        List<JackpotTable.Entry> jackpotEntries = new ArrayList<>();
        Set<ItemType> jackpotItemTypes = new HashSet<>();
        for (Map.Entry<String, Integer> configured : settings.jackpotItemWeights().entrySet()) {
            int weight = configured.getValue();
            if (weight <= 0 || exclusions.contains(configured.getKey())) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(configured.getKey());
            ItemType itemType = key == null ? null : Registry.ITEM.get(key);
            if (itemType == null || !world.isEnabled(itemType)) {
                continue;
            }
            // totem_of_undying doesn't match the tool/weapon/armor suffixes ItemClassifier
            // looks for, but it is unmistakably a "gear" reward worth rate-limiting like one.
            boolean equipment = ItemClassifier.classify(itemType).isEquipment() || itemType == ItemType.TOTEM_OF_UNDYING;
            int amount = itemType == ItemType.ENDER_PEARL ? 3 : 1;
            jackpotEntries.add(new JackpotTable.Entry(itemType, weight, amount, equipment));
            jackpotItemTypes.add(itemType);
        }
        this.jackpotTable = new JackpotTable(jackpotEntries);

        Map<ItemCategory, List<ItemType>> pools = new EnumMap<>(ItemCategory.class);
        for (ItemCategory category : ItemCategory.values()) {
            pools.put(category, new ArrayList<>());
        }
        int permittedCount = 0;
        for (ItemType item : Registry.ITEM) {
            if (item.getKey().getKey().equals("air")
                    || !world.isEnabled(item)
                    || exclusions.contains(item.getKey().toString())
                    || jackpotItemTypes.contains(item)) {
                continue;
            }
            pools.get(ItemClassifier.classify(item)).add(item);
            permittedCount++;
        }
        for (ItemCategory category : ItemCategory.values()) {
            pools.put(category, List.copyOf(pools.get(category)));
        }
        Map<ItemCategory, List<ItemType>> categoryPools = Map.copyOf(pools);
        this.size = permittedCount;

        this.timedProfile = new WeightedLootProfile(settings.timedCategoryWeights(), categoryPools);
        Map<ItemCategory, Integer> luckyCategoryWeights = new EnumMap<>(settings.luckyCategoryWeights());
        this.luckyJackpotWeight = Math.max(0, luckyCategoryWeights.remove(ItemCategory.JACKPOT));
        this.luckyProfile = new WeightedLootProfile(luckyCategoryWeights, categoryPools);

        if (timedProfile.totalEligibleWeight(category -> true) <= 0) {
            throw new IllegalStateException(
                    "the timed loot profile has no selectable items; check random-items.timed-weights and random-items.excluded");
        }
        if (luckyProfile.totalEligibleWeight(category -> true) <= 0 && this.jackpotTable.isEmpty()) {
            throw new IllegalStateException(
                    "the lucky-block loot profile has no selectable items; check random-items.lucky-weights, "
                            + "random-items.jackpot-items, and random-items.excluded");
        }
    }

    int size() {
        return size;
    }

    void give(Player player, String label) {
        ItemType selected = timedProfile.roll(category -> true);
        PlayerGiveResult result = player.give(createItemStack(selected, 1));
        player.sendActionBar(Component.text(label + ": ", NamedTextColor.GREEN)
                .append(Component.translatable(selected.translationKey()).color(NamedTextColor.GOLD)));
        if (!result.drops().isEmpty()) {
            player.sendActionBar(Component.text("Your inventory was full, so the random item was dropped at your feet.",
                    NamedTextColor.RED));
        }
    }

    void drop(Location location) {
        ItemType selected = timedProfile.roll(category -> true);
        location.getWorld().dropItemNaturally(location, createItemStack(selected, 1));
    }

    void giveLucky(Player player, String label) {
        LootPick pick = rollLucky(player.getUniqueId());
        PlayerGiveResult result = player.give(pick.stack());
        player.sendActionBar(Component.text(label + ": ", NamedTextColor.GREEN)
                .append(Component.translatable(pick.itemType().translationKey()).color(NamedTextColor.GOLD)));
        if (!result.drops().isEmpty()) {
            player.sendActionBar(Component.text("Your inventory was full, so the lucky item was dropped at your feet.",
                    NamedTextColor.RED));
        }
    }

    void dropLucky(Location location, UUID playerId) {
        LootPick pick = rollLucky(playerId);
        location.getWorld().dropItemNaturally(location, pick.stack());
    }

    /** Clears every player's lucky-roll history and equipment cooldown; call this on match reset. */
    void resetLuckyHistory() {
        luckyHistory.reset();
    }

    private LootPick rollLucky(UUID playerId) {
        boolean equipmentAllowed = !luckyHistory.isInEquipmentCooldown(playerId);

        LuckyCandidate chosen = rollLuckyOnce(equipmentAllowed);
        for (int attempt = 1; attempt < MAX_RECENCY_REROLLS && luckyHistory.isRecent(playerId, chosen.itemType()); attempt++) {
            chosen = rollLuckyOnce(equipmentAllowed);
        }

        luckyHistory.record(playerId, chosen.itemType(), chosen.equipment());
        ItemStack stack = createItemStack(chosen.itemType(), chosen.amount());
        return new LootPick(stack, chosen.itemType());
    }

    /** One weighted pick across the lucky categories plus the jackpot table as one more "category". */
    private LuckyCandidate rollLuckyOnce(boolean equipmentAllowed) {
        Predicate<ItemCategory> filter = equipmentAllowed ? category -> true : category -> !category.isEquipment();
        int categoryWeight = luckyProfile.totalEligibleWeight(filter);
        int jackpotNonEquipmentWeight = jackpotTable.totalWeight(false);
        int effectiveJackpotWeight = equipmentAllowed
                ? luckyJackpotWeight
                : (jackpotNonEquipmentWeight > 0 ? luckyJackpotWeight : 0);

        int total = categoryWeight + effectiveJackpotWeight;
        if (total <= 0) {
            // Shouldn't happen (validated at startup), but never crash mid-match over it.
            filter = category -> true;
            categoryWeight = luckyProfile.totalEligibleWeight(filter);
            effectiveJackpotWeight = luckyJackpotWeight;
            total = categoryWeight + effectiveJackpotWeight;
        }

        int roll = ThreadLocalRandom.current().nextInt(total);
        if (roll < categoryWeight) {
            ItemType picked = luckyProfile.roll(filter);
            return new LuckyCandidate(picked, 1, ItemClassifier.classify(picked).isEquipment());
        }
        JackpotTable.Entry entry = jackpotTable.roll(equipmentAllowed);
        return new LuckyCandidate(entry.itemType(), entry.amount(), entry.equipment());
    }

    private static ItemStack createItemStack(ItemType itemType, int amount) {
        ItemStack stack = itemType.createItemStack(amount);
        if (ItemClassifier.classify(itemType).isEquipment()) {
            EquipmentEnchantments.applyMaximumCompatibleEnchantments(stack);
        }
        return stack;
    }

    private record LootPick(ItemStack stack, ItemType itemType) {
    }

    private record LuckyCandidate(ItemType itemType, int amount, boolean equipment) {
    }
}
