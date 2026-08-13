package com.pirro.minigames.plugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

record GameSettings(
        String worldName,
        int pillarTopY,
        int pillarRadius,
        int bedrockHeight,
        boolean rescueFromVoid,
        int roundCount,
        int roundDurationTicks,
        int firstItemIntervalTicks,
        int itemIntervalReductionTicks,
        int doubleJumpRound,
        int tripleJumpRound,
        double jumpVelocity,
        double jumpForwardVelocity,
        int jumpClickCooldownTicks,
        Set<String> excludedItemKeys,
        Map<ItemCategory, Integer> timedCategoryWeights,
        Map<ItemCategory, Integer> luckyCategoryWeights,
        Map<String, Integer> jackpotItemWeights
) {
    // random-items.timed-weights defaults: normal timed-phase rewards use balanced category weights.
    private static final Map<ItemCategory, Integer> DEFAULT_TIMED_WEIGHTS = Map.of(
            ItemCategory.BLOCKS, 30,
            ItemCategory.WEAPONS, 15,
            ItemCategory.ARMOR, 10,
            ItemCategory.TOOLS, 10,
            ItemCategory.FOOD, 15,
            ItemCategory.PROJECTILES, 8,
            ItemCategory.UTILITY, 8,
            ItemCategory.CHAOS, 4
    );

    // random-items.lucky-weights defaults: tuned for roughly one lucky-block break per player
    // per second, so blocks/food dominate and equipment/jackpot stay rare.
    private static final Map<ItemCategory, Integer> DEFAULT_LUCKY_WEIGHTS = Map.of(
            ItemCategory.BLOCKS, 5500,
            ItemCategory.FOOD, 2000,
            ItemCategory.PROJECTILES, 1200,
            ItemCategory.UTILITY, 1000,
            ItemCategory.TOOLS, 150,
            ItemCategory.WEAPONS, 100,
            ItemCategory.ARMOR, 40,
            ItemCategory.JACKPOT, 10
    );

    // random-items.jackpot-items defaults: the weighted table rolled when a lucky-block break
    // lands on the JACKPOT category.
    private static final Map<String, Integer> DEFAULT_JACKPOT_WEIGHTS = defaultJackpotWeights();

    private static Map<String, Integer> defaultJackpotWeights() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("totem_of_undying", 30);
        defaults.put("enchanted_golden_apple", 25);
        defaults.put("netherite_chestplate", 12);
        defaults.put("netherite_sword", 10);
        defaults.put("mace", 12);
        defaults.put("trident", 8);
        defaults.put("ender_pearl", 3);
        return Map.copyOf(defaults);
    }

    static GameSettings from(FileConfiguration config) {
        String worldName = config.getString("world.name", "oneblock_void").trim();
        if (worldName.isBlank() || worldName.contains("/") || worldName.contains("\\")) {
            throw new IllegalArgumentException("world.name must be a simple, non-empty world name");
        }

        int pillarTopY = config.contains("pillars.top-y", true)
                ? rangedInt(config, "pillars.top-y", 96, -63, 318)
                : rangedInt(config, "pillars.generator-y", 96, -63, 318);
        int pillarRadius = rangedInt(config, "pillars.radius", 32, 8, 512);
        int bedrockHeight = rangedInt(config, "pillars.bedrock-height", 8, 1, 128);
        if (pillarTopY - bedrockHeight < -64) {
            throw new IllegalArgumentException("the pillar bedrock column cannot extend below Y=-64");
        }

        int roundCount = rangedInt(config, "rounds.count", 5, 2, 100);
        int roundDurationTicks = secondsToTicks(
                config,
                "rounds.duration-seconds",
                70.0,
                0.05,
                3600.0
        );
        int firstItemIntervalTicks = secondsToTicks(
                config,
                "rounds.first-item-interval-seconds",
                15.0,
                0.05,
                3600.0
        );
        int intervalReductionTicks = secondsToTicks(
                config,
                "rounds.item-interval-reduction-seconds",
                2.5,
                0.0,
                3600.0
        );

        int finalIntervalTicks = firstItemIntervalTicks - intervalReductionTicks * (roundCount - 1);
        if (finalIntervalTicks < 1) {
            throw new IllegalArgumentException(
                    "the final random-item interval must be at least one server tick"
            );
        }
        if (firstItemIntervalTicks > roundDurationTicks) {
            throw new IllegalArgumentException(
                    "rounds.first-item-interval-seconds cannot exceed rounds.duration-seconds"
            );
        }

        int doubleJumpRound = rangedInt(
                config,
                "abilities.double-jump-round",
                2,
                1,
                roundCount
        );
        int tripleJumpRound = rangedInt(
                config,
                "abilities.triple-jump-round",
                3,
                1,
                roundCount
        );
        if (doubleJumpRound >= tripleJumpRound) {
            throw new IllegalArgumentException(
                    "abilities.double-jump-round must be before abilities.triple-jump-round"
            );
        }

        double jumpVelocity = rangedDouble(config, "abilities.jump-velocity", 0.65, 0.1, 3.0);
        double jumpForwardVelocity = rangedDouble(
                config,
                "abilities.jump-forward-velocity",
                0.85,
                0.0,
                3.0
        );
        int clickCooldownTicks = rangedInt(config, "abilities.click-cooldown-ticks", 4, 1, 100);
        Set<String> exclusions = config.getStringList("random-items.excluded").stream()
                .map(GameSettings::normalizeItemKey)
                .collect(Collectors.toUnmodifiableSet());
        Map<ItemCategory, Integer> timedWeights = readCategoryWeights(
                config, "random-items.timed-weights", DEFAULT_TIMED_WEIGHTS);
        Map<ItemCategory, Integer> luckyWeights = readCategoryWeights(
                config, "random-items.lucky-weights", DEFAULT_LUCKY_WEIGHTS);
        Map<String, Integer> jackpotWeights = readJackpotWeights(
                config, "random-items.jackpot-items", DEFAULT_JACKPOT_WEIGHTS);

        return new GameSettings(
                worldName,
                pillarTopY,
                pillarRadius,
                bedrockHeight,
                config.getBoolean("rescue-from-void", true),
                roundCount,
                roundDurationTicks,
                firstItemIntervalTicks,
                intervalReductionTicks,
                doubleJumpRound,
                tripleJumpRound,
                jumpVelocity,
                jumpForwardVelocity,
                clickCooldownTicks,
                exclusions,
                timedWeights,
                luckyWeights,
                jackpotWeights
        );
    }

    int itemIntervalTicksForRound(int zeroBasedRound) {
        return firstItemIntervalTicks - itemIntervalReductionTicks * zeroBasedRound;
    }

    /** The largest tick period that evenly divides the round duration and every round's item interval. */
    int clockPeriodTicks() {
        int period = roundDurationTicks;
        for (int round = 0; round < roundCount; round++) {
            period = greatestCommonDivisor(period, itemIntervalTicksForRound(round));
        }
        return period;
    }

    private static int greatestCommonDivisor(int first, int second) {
        int left = Math.abs(first);
        int right = Math.abs(second);
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    /**
     * Reads a category-weight table, falling back to defaultWeights per-key so a config that
     * only overrides one or two categories doesn't have to restate the whole table (and a
     * config that omits the section entirely - the pre-existing-config case - uses it as-is).
     */
    private static Map<ItemCategory, Integer> readCategoryWeights(
            FileConfiguration config,
            String path,
            Map<ItemCategory, Integer> defaultWeights
    ) {
        Map<ItemCategory, Integer> weights = new EnumMap<>(ItemCategory.class);
        for (Map.Entry<ItemCategory, Integer> entry : defaultWeights.entrySet()) {
            String key = path + "." + entry.getKey().configKey();
            int value = config.getInt(key, entry.getValue());
            if (value < 0) {
                throw new IllegalArgumentException(key + " must not be negative");
            }
            weights.put(entry.getKey(), value);
        }
        return Map.copyOf(weights);
    }

    /** Same per-key-fallback approach as readCategoryWeights, but also allows new item keys. */
    private static Map<String, Integer> readJackpotWeights(
            FileConfiguration config,
            String path,
            Map<String, Integer> defaultWeights
    ) {
        Map<String, Integer> weights = new LinkedHashMap<>(defaultWeights);
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null) {
            for (String rawKey : section.getKeys(false)) {
                int value = section.getInt(rawKey);
                if (value < 0) {
                    throw new IllegalArgumentException(path + "." + rawKey + " must not be negative");
                }
                weights.put(normalizeItemKey(rawKey), value);
            }
        }
        return Map.copyOf(weights);
    }

    private static int rangedInt(
            FileConfiguration config,
            String path,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        int value = config.getInt(path, defaultValue);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static double rangedDouble(
            FileConfiguration config,
            String path,
            double defaultValue,
            double minimum,
            double maximum
    ) {
        double value = config.getDouble(path, defaultValue);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static int secondsToTicks(
            FileConfiguration config,
            String path,
            double defaultValue,
            double minimum,
            double maximum
    ) {
        double seconds = rangedDouble(config, path, defaultValue, minimum, maximum);
        double exactTicks = seconds * 20.0;
        int ticks = (int) Math.round(exactTicks);
        if (Math.abs(exactTicks - ticks) > 0.000_001) {
            throw new IllegalArgumentException(path + " must be a multiple of 0.05 seconds");
        }
        return ticks;
    }

    private static String normalizeItemKey(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }
}
