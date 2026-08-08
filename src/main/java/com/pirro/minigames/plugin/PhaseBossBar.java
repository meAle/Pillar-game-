package com.pirro.minigames.plugin;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Owns the phase countdown boss bar and its player visibility. */
final class PhaseBossBar {
    private final World world;
    private final GameSettings settings;
    private final BossBar bossBar;

    PhaseBossBar(World world, GameSettings settings) {
        this.world = world;
        this.settings = settings;
        this.bossBar = BossBar.bossBar(
                Component.text("Waiting for the next game"),
                1.0F,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );
    }

    void update(int zeroBasedPhase, int elapsedTicks) {
        if (zeroBasedPhase < 0) {
            return;
        }

        int displayedPhase = zeroBasedPhase + 1;
        if (displayedPhase == settings.roundCount()) {
            bossBar.name(Component.text("Phase " + displayedPhase + "/" + settings.roundCount()
                    + " - Final Phase", NamedTextColor.RED));
            bossBar.progress(1.0F);
            bossBar.color(BossBar.Color.RED);
            return;
        }

        int remainingTicks = Math.max(0, settings.roundDurationTicks() - Math.max(0, elapsedTicks));
        float progress = Math.max(0.0F, Math.min(1.0F,
                (float) remainingTicks / settings.roundDurationTicks()));
        bossBar.name(Component.text("Phase " + displayedPhase + "/" + settings.roundCount()
                + " - " + formatClock(remainingTicks), NamedTextColor.YELLOW));
        bossBar.progress(progress);
        bossBar.color(BossBar.Color.YELLOW);
    }

    void showTo(Player player) {
        player.showBossBar(bossBar);
    }

    void hideFrom(Player player) {
        player.hideBossBar(bossBar);
    }

    void showToAll() {
        for (Player player : world.getPlayers()) {
            showTo(player);
        }
    }

    void hideFromAll() {
        for (Player player : world.getPlayers()) {
            hideFrom(player);
        }
    }

    private static String formatClock(int ticks) {
        int totalSeconds = (ticks + 19) / 20;
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
