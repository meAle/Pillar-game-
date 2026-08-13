package com.pirro.minigames.plugin;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the sidebar's stat lines (streaks, phase, lives, ready count) and
 * forwards them to PillarGameSidebar. Round/phase state is read from a
 * snapshot supplied by the caller rather than tracked here, so this class
 * has no idea what round it is on its own. Deliberately excludes anything
 * that changes sub-second (e.g. a countdown) so callers never need to poll
 * this on a tight timer - refreshing on state changes is enough.
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
            lines.add("Phase: " + (snapshot.roundIndex() + 1) + "/" + settings.roundCount());
            lines.add("Lives: " + playerLives.lives(playerId));
        } else if (readiness.isOpen()) {
            lines.add("Ready: " + readiness.readyCount() + "/" + readiness.requiredReadyCount());
        }
        return lines;
    }

    /** roundIndex is only meaningful while started is true. */
    record RoundSnapshot(boolean started, boolean finished, int roundIndex) {
    }
}
