package com.pirro.minigames.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Files;
import java.nio.file.Path;

final class GameWorldFactory {
    private static final int LAYOUT_VERSION = 1;

    private GameWorldFactory() {
    }

    static World createOrLoad(MiniGames plugin, GameSettings settings) {
        World alreadyLoaded = Bukkit.getWorld(settings.worldName());
        Path worldFolder = Bukkit.getWorldContainer().toPath().resolve(settings.worldName()).normalize();
        boolean existedBeforeStartup = alreadyLoaded != null || Files.exists(worldFolder);

        VoidChunkGenerator generator = new VoidChunkGenerator(
                settings.pillarRadius(),
                settings.pillarTopY(),
                0
        );

        World world = alreadyLoaded;
        if (world == null) {
            world = new WorldCreator(settings.worldName())
                    .environment(World.Environment.NORMAL)
                    .generator(generator)
                    .generateStructures(false)
                    .createWorld();
        }
        if (world == null) {
            throw new IllegalStateException("Paper returned no world for '" + settings.worldName() + "'");
        }

        NamespacedKey markerKey = new NamespacedKey(plugin, "layout-version");
        PersistentDataContainer data = world.getPersistentDataContainer();
        Integer existingVersion = data.get(markerKey, PersistentDataType.INTEGER);

        if (existedBeforeStartup && existingVersion == null) {
            throw new IllegalStateException("world '" + settings.worldName()
                    + "' already exists but was not created by MiniGames; choose another world.name");
        }
        if (existingVersion != null && existingVersion != LAYOUT_VERSION) {
            throw new IllegalStateException("world '" + settings.worldName()
                    + "' uses unsupported MiniGames layout version " + existingVersion);
        }

        validateHeight(world, settings);
        data.set(markerKey, PersistentDataType.INTEGER, LAYOUT_VERSION);
        configureRules(world);
        world.save();
        return world;
    }

    private static void validateHeight(World world, GameSettings settings) {
        int bedrockBottom = settings.pillarTopY() - settings.bedrockHeight();
        if (bedrockBottom < world.getMinHeight() || settings.pillarTopY() + 1 >= world.getMaxHeight()) {
            throw new IllegalArgumentException("pillar heights do not fit world bounds "
                    + world.getMinHeight() + ".." + (world.getMaxHeight() - 1));
        }
    }

    private static void configureRules(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        world.setGameRule(GameRules.SPECTATORS_GENERATE_CHUNKS, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6000L);
    }
}