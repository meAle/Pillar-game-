package com.pirro.minigames.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Renders the per-player sidebar scoreboard. Callers supply the lines to
 * show for a player; this class only owns the scoreboard plumbing.
 */
final class PillarGameSidebar {
    private static final String[] LINE_PREFIXES = {
            "\u00A70\u00A7f", "\u00A71\u00A7f", "\u00A72\u00A7f", "\u00A73\u00A7f",
            "\u00A74\u00A7f", "\u00A75\u00A7f", "\u00A76\u00A7f", "\u00A77\u00A7f",
            "\u00A78\u00A7f", "\u00A79\u00A7f", "\u00A7a\u00A7f", "\u00A7b\u00A7f",
            "\u00A7c\u00A7f", "\u00A7d\u00A7f", "\u00A7e\u00A7f", "\u00A7f\u00A7f"
    };

    private final World world;
    private final Map<UUID, Scoreboard> sidebars = new HashMap<>();

    PillarGameSidebar(World world) {
        this.world = world;
    }

    void refreshAll(Function<Player, List<String>> lineSource) {
        for (Player player : world.getPlayers()) {
            update(player, lineSource.apply(player));
        }
    }

    void update(Player player, List<String> lines) {
        Scoreboard board = sidebars.computeIfAbsent(player.getUniqueId(), ignored ->
                Bukkit.getScoreboardManager().getNewScoreboard());
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }

        Objective previous = board.getObjective("minigames");
        if (previous != null) {
            previous.unregister();
        }
        if (lines.isEmpty()) {
            return;
        }
        Objective objective = board.registerNewObjective(
                "minigames",
                Criteria.DUMMY,
                Component.text("MiniGames", NamedTextColor.AQUA)
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = lines.size();
        for (int index = 0; index < lines.size(); index++) {
            String entry = LINE_PREFIXES[index] + shorten(lines.get(index), 34);
            objective.getScore(entry).setScore(score--);
        }
    }

    void remove(Player player) {
        sidebars.remove(player.getUniqueId());
    }

    void clear() {
        sidebars.clear();
    }

    private static String shorten(String value, int maximumLength) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLine.length() <= maximumLength) {
            return singleLine;
        }
        return singleLine.substring(0, maximumLength - 1) + "…";
    }
}