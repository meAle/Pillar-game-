package com.pirro.minigames.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the sidebar's stat lines (streaks, phase, lives, next item, ready
 * count) and forwards them to PillarGameSidebar. Round/phase state is read
 * from a snapshot supplied by the caller rather than tracked here, so this
 * class has no idea what round it is on its own.
 */
final class PillarGameSidebarPresenter {
    private final PillarGameSidebar sidebar;
    private final GameSettings settings;
    private final PlayerLifeManager playerLives;
    private final ResultsStore results;
    private final ReadyManager readiness;

    PillarGameSidebarPresenter(
            PillarGameSidebar sidebar,
            GameSettings settings,
            PlayerLifeManager playerLives,
            ResultsStore results,
            ReadyManager readiness
    ) {
        this.sidebar = sidebar;
        this.settings = settings;
        this.playerLives = playerLives;
        this.results = results;
        this.readiness = readiness;
    }

    void refreshAll(RoundSnapshot snapshot) {
        sidebar.refreshAll(player -> lines(player, snapshot));
    }

    void update(Player player, RoundSnapshot snapshot) {
        sidebar.update(player, lines(player, snapshot));
    }

    void remove(Player player) {
        sidebar.remove(player);
    }

    void clear() {
        sidebar.clear();
    }

    private List<String> lines(Player player, RoundSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        UUID playerId = player.getUniqueId();
        lines.add("Win streak: " + results.getCurrentStreak(playerId));
        lines.add("Best streak: " + results.getBestStreak(playerId));
        ResultsStore.StreakLeader leader = results.getStreakLeader();
        lines.add("Streak leader: " + leader.playerName() + " " + leader.streak());

        if (snapshot.started() && !snapshot.finished()) {
            int intervalTicks = settings.itemIntervalTicksForRound(snapshot.roundIndex());
            int elapsedSinceItem = Math.floorMod(Bukkit.getCurrentTick() - snapshot.roundStartedAtTick(), intervalTicks);
            int nextItemTicks = elapsedSinceItem == 0 ? intervalTicks : intervalTicks - elapsedSinceItem;

            lines.add("Phase: " + (snapshot.roundIndex() + 1) + "/" + settings.roundCount());
            lines.add("Lives: " + playerLives.lives(playerId));
            lines.add("Next item: " + formatTenths(nextItemTicks) + "s");
        } else if (readiness.isOpen()) {
            lines.add("Ready: " + readiness.readyCount() + "/" + readiness.requiredReadyCount());
        }
        return lines;
    }

    private static String formatTenths(int ticks) {
        return BigDecimal.valueOf(ticks)
                .divide(BigDecimal.valueOf(20), 1, RoundingMode.DOWN)
                .toPlainString();
    }

    /** currentRoundIndex/roundStartedAtTick are only meaningful while started is true. */
    record RoundSnapshot(boolean started, boolean finished, int roundIndex, int roundStartedAtTick) {
    }
}
