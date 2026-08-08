package com.pirro.minigames.plugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persists per-player round-win counts and win streaks to results.yml.
 */
final class ResultsStore {
    private final MiniGames plugin;
    private final File resultsFile;
    private final YamlConfiguration results;

    ResultsStore(MiniGames plugin) {
        this.plugin = plugin;
        this.resultsFile = new File(plugin.getDataFolder(), "results.yml");
        this.results = YamlConfiguration.loadConfiguration(resultsFile);
    }

    void recordWin(Player winner, Collection<UUID> participants) {
        UUID winnerId = winner.getUniqueId();
        String winnerPath = playerPath(winnerId);
        long currentStreak = getCurrentStreak(winnerId) + 1L;

        results.set(winnerPath + ".name", winner.getName());
        results.set(winnerPath + ".wins", results.getLong(winnerPath + ".wins", 0L) + 1L);
        results.set(winnerPath + ".current-streak", currentStreak);
        results.set(winnerPath + ".best-streak", Math.max(getBestStreak(winnerId), currentStreak));

        for (UUID participantId : participants) {
            if (!participantId.equals(winnerId)) {
                results.set(playerPath(participantId) + ".current-streak", 0L);
            }
        }

        save();
    }

    long getCurrentStreak(UUID playerId) {
        return results.getLong(playerPath(playerId) + ".current-streak", 0L);
    }

    long getBestStreak(UUID playerId) {
        return results.getLong(playerPath(playerId) + ".best-streak", 0L);
    }

    StreakLeader getStreakLeader() {
        ConfigurationSection players = results.getConfigurationSection("players");
        StreakLeader leader = new StreakLeader("None", 0L);
        if (players == null) {
            return leader;
        }

        for (String playerId : players.getKeys(false)) {
            String path = "players." + playerId;
            long streak = results.getLong(path + ".current-streak", 0L);
            String name = results.getString(path + ".name", playerId);
            if (streak > leader.streak()
                    || (streak == leader.streak() && streak > 0L
                    && name.compareToIgnoreCase(leader.playerName()) < 0)) {
                leader = new StreakLeader(name, streak);
            }
        }
        return leader;
    }

    private static String playerPath(UUID playerId) {
        return "players." + playerId;
    }

    private void save() {
        try {
            results.save(resultsFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save results.yml.", exception);
        }
    }

    record StreakLeader(String playerName, long streak) {
    }
}
