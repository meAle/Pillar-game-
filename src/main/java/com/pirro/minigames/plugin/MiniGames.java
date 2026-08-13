package com.pirro.minigames.plugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

public final class MiniGames extends JavaPlugin {

    private PillarGame pillarGame;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            GameSettings settings = GameSettings.from(getConfig());
            World gameWorld = GameWorldFactory.createOrLoad(this, settings);

            pillarGame = new PillarGame(this, gameWorld, settings);
            pillarGame.initialize();

            getServer().getPluginManager().registerEvents(pillarGame.playerListener(), this);
            getServer().getPluginManager().registerEvents(pillarGame.arena(), this);
            getServer().getPluginManager().registerEvents(pillarGame.jumpFeathers(), this);
            getServer().getPluginManager().registerEvents(pillarGame.readiness(), this);
            getServer().getPluginManager().registerEvents(pillarGame.luckyBlocks(), this);
            getServer().getPluginManager().registerEvents(pillarGame.centerLootChest(), this);
            getServer().getPluginManager().registerEvents(pillarGame.droppedItemDespawn(), this);
            getServer().getPluginManager().registerEvents(pillarGame.enderDragonMovement(), this);

            PillarGameCommands commands = new PillarGameCommands(pillarGame);
            Objects.requireNonNull(getCommand("minigames"), "minigames command missing from plugin.yml")
                    .setExecutor(commands);
            Objects.requireNonNull(getCommand("minigames"), "minigames command missing from plugin.yml")
                    .setTabCompleter(commands);
            Objects.requireNonNull(getCommand("ready"), "ready command missing from plugin.yml")
                    .setExecutor(commands);
            Objects.requireNonNull(getCommand("ready"), "ready command missing from plugin.yml")
                    .setTabCompleter(commands);

            // Covers a plugin reload. Normal connections are placed by
            // AsyncPlayerSpawnLocationEvent before PlayerJoinEvent fires.
            for (Player player : Bukkit.getOnlinePlayers()) {
                pillarGame.placeExistingPlayer(player);
            }

            getLogger().info("MiniGames is ready: 8 random-item pillars in world '"
                    + gameWorld.getName() + "'.");
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "MiniGames could not start", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (pillarGame != null) {
            pillarGame.close();
            pillarGame = null;
        }
    }
}
