package com.pirro.minigames.plugin;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bukkit-facing player lifecycle events (join/quit/death/respawn/move/world
 * change/void damage/initial spawn) for the pillar game. Holds no game state
 * of its own; every handler just reads or mutates PillarGame's state through
 * its package-private API.
 */
final class PillarGamePlayerListener implements Listener {
    private final PillarGame game;

    PillarGamePlayerListener(PillarGame game) {
        this.game = game;
    }

    @EventHandler
    public void onInitialSpawn(AsyncPlayerSpawnLocationEvent event) {
        UUID playerId = event.getConnection().getProfile().getId();
        if (playerId == null) {
            String name = event.getConnection().getProfile().getName();
            playerId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        event.setSpawnLocation(game.arena().spawnLocation(game.arena().assignSlot(playerId)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        game.preparePlayerForLobby(player);

        if (game.isGameFinished()) {
            game.phaseBossBar().hideFrom(player);
            game.updateSidebar(player);
            return;
        }
        if (game.readiness().isOpen()) {
            player.sendMessage(Component.text("Right-click the dye or use /ready when you are ready.",
                    NamedTextColor.AQUA));
            game.readiness().addPlayer(player);
            game.readiness().handlePlayerCountChanged();
            game.updateSidebar(player);
            return;
        }
        if (!game.isGameStarted()) {
            game.startVoting();
            return;
        }

        game.jumpFeathers().removeAll(player);
        player.setGameMode(GameMode.SPECTATOR);
        game.phaseBossBar().showTo(player);
        game.updateSidebar(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (game.isRoundAlive(player)) {
            game.eliminatePlayer(player, "left the game", false);
        }
        UUID playerId = player.getUniqueId();
        game.arena().forgetAssignment(playerId);
        game.readiness().forgetPlayer(playerId);
        game.plugin().getServer().getScheduler().runTask(game.plugin(), game.readiness()::handlePlayerCountChanged);
        game.jumpFeathers().clearState(player);
        game.playerLives().forgetPendingSpectator(playerId);
        game.forgetSidebar(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!game.isGameWorld(player) && !game.playerLives().hasParticipant(player.getUniqueId())) {
            return;
        }

        event.setRespawnLocation(game.arena().safestRespawnLocation(player.getUniqueId()));
        game.plugin().getServer().getScheduler().runTask(game.plugin(), () -> game.restoreAfterRespawn(player));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        game.jumpFeathers().clearState(player);
        event.getDrops().removeIf(game.jumpFeathers()::isJumpFeather);
        if (game.isRoundAlive(player)) {
            game.consumeLife(player, "died", true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo().getY() < 0.0 && game.isRoundAlive(player)) {
            game.consumeLife(player, "died", false);
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        game.jumpFeathers().clearState(player);
        if (event.getFrom().getUID().equals(game.world().getUID()) && !game.isGameWorld(player)
                && game.playerLives().hasParticipant(player.getUniqueId())
                && !game.playerLives().isEliminated(player.getUniqueId())) {
            game.eliminatePlayer(player, "left the arena", false);
        }
        if (game.isGameWorld(player)) {
            if (game.isGameStarted() && !game.isGameFinished()) {
                game.phaseBossBar().showTo(player);
            } else {
                game.phaseBossBar().hideFrom(player);
            }
            if (game.isRoundAlive(player)) {
                game.jumpFeathers().synchronize(player);
            }
            game.updateSidebar(player);
        } else {
            game.phaseBossBar().hideFrom(player);
            game.jumpFeathers().removeAll(player);
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            game.forgetSidebar(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID
                || !(event.getEntity() instanceof Player player)
                || !game.isGameWorld(player)) {
            return;
        }

        if (game.isRoundAlive(player)) {
            event.setCancelled(true);
            game.consumeLife(player, "fell into the void", false);
            return;
        }
        if (game.settings().rescueFromVoid()) {
            event.setCancelled(true);
            game.sendToPillar(player);
        }
    }
}
