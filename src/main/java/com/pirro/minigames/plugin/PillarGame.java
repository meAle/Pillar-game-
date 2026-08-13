package com.pirro.minigames.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The phase/game state machine: who's alive, which phase is active, when a phase
 * ends, and the round/game transitions (start, advance, finish, restart) that
 * follow from it. Bukkit player-lifecycle events are handled by
 * PillarGamePlayerListener, which calls back into this class's package-private
 * API. Terrain, the jump ability, voting, the sidebar, random items, and win
 * persistence are all delegated to their own classes below.
 */
final class     PillarGame {
    private static final int LIVES_PER_GAME = 3;

    private final MiniGames plugin;
    private final World world;
    private final GameSettings settings;
    private final int clockPeriodTicks;

    private final PillarArena arena;
    private final JumpFeatherService jumpFeathers;
    private final RandomItemPool items;
    private final ResultsStore results;
    private final ReadyManager readiness;
    private final PillarGameSidebarPresenter sidebar;
    private final PhaseBossBar phaseBossBar;
    private final PlayerLifeManager playerLives;
    private final LuckyBlockService luckyBlocks;
    private final CenterLootChestService centerLootChest;
    private final DroppedItemDespawnService droppedItemDespawn;
    private final EnderDragonMovementService enderDragonMovement;
    private final PillarGamePlayerListener playerListener;

    private BukkitTask gameClockTask;
    private BukkitTask bossBarTask;
    private int currentRoundIndex = -1;
    private int elapsedRoundTicks;
    private int roundStartedAtTick;
    private boolean gameStarted;
    private boolean gameFinished;
    private boolean roundResolved;
    private boolean roundTransitionQueued;

    PillarGame(MiniGames plugin, World world, GameSettings settings) {
        this.plugin = plugin;
        this.world = world;
        this.settings = settings;
        this.clockPeriodTicks = settings.clockPeriodTicks();

        this.arena = new PillarArena(world, settings);
        this.items = new RandomItemPool(world, settings.excludedItemKeys());
        this.results = new ResultsStore(plugin);
        this.phaseBossBar = new PhaseBossBar(world, settings);
        this.playerLives = new PlayerLifeManager(LIVES_PER_GAME);
        this.luckyBlocks = new LuckyBlockService(plugin, world, arena.luckyBlockLocations(), items);
        this.centerLootChest = new CenterLootChestService(world, arena.luckyBlockLocations(), luckyBlocks);
        this.droppedItemDespawn = new DroppedItemDespawnService(plugin, world);
        this.enderDragonMovement = new EnderDragonMovementService(
                plugin, world, settings, () -> gameStarted && !gameFinished);
        this.readiness = new ReadyManager(plugin, world, this::refreshSidebars, this::startGame);
        this.sidebar = new PillarGameSidebarPresenter(
                new PillarGameSidebar(world), settings, playerLives, results, readiness);
        this.jumpFeathers = new JumpFeatherService(plugin, world, settings, this::isRoundAlive, this::currentJumpTier);
        this.playerListener = new PillarGamePlayerListener(this);
    }

    void initialize() {
        arena.initialize();
        plugin.getLogger().info("Random item pool contains " + items.size() + " items and blocks.");
    }

    /** Listeners other than the player-lifecycle one that also need to be registered with Bukkit. */
    PillarArena arena() {
        return arena;
    }

    JumpFeatherService jumpFeathers() {
        return jumpFeathers;
    }

    ReadyManager readiness() {
        return readiness;
    }

    LuckyBlockService luckyBlocks() {
        return luckyBlocks;
    }

    CenterLootChestService centerLootChest() {
        return centerLootChest;
    }

    DroppedItemDespawnService droppedItemDespawn() {
        return droppedItemDespawn;
    }

    EnderDragonMovementService enderDragonMovement() {
        return enderDragonMovement;
    }

    PillarGamePlayerListener playerListener() {
        return playerListener;
    }

    void placeExistingPlayer(Player player) {
        preparePlayerForLobby(player);
        if (!gameStarted && !gameFinished && !readiness.isOpen()) {
            startVoting();
        } else if (gameStarted && isRoundAlive(player)) {
            jumpFeathers.synchronize(player);
        }
    }

    void close() {
        cancelGameClock();
        readiness.cancel();
        arena.clearAssignments();
        jumpFeathers.reset();
        luckyBlocks.stop();
        centerLootChest.stop();
        droppedItemDespawn.close();
        playerLives.reset();
        phaseBossBar.hideFromAll();
        sidebar.clear();
    }

    // ------------------------------------------------------------------
    // Command-facing API (used by PillarGameCommands)
    // ------------------------------------------------------------------

    boolean isGameWorld(Player player) {
        return player.getWorld().getUID().equals(world.getUID());
    }

    boolean isReadyCheckOpen() {
        return readiness.isOpen();
    }

    boolean isGameStarted() {
        return gameStarted;
    }

    void toggleReady(Player player) {
        readiness.toggle(player);
    }

    Collection<Player> playersInArena() {
        return List.copyOf(world.getPlayers());
    }

    void giveItems(Collection<? extends Player> targets, int amount) {
        for (Player target : targets) {
            for (int index = 0; index < amount; index++) {
                items.give(target, "Forced items");
            }
        }
    }

    void giveFeathers(Collection<? extends Player> targets, int jumpTier) {
        for (Player target : targets) {
            jumpFeathers.giveFeather(target, jumpTier);
        }
    }

    void forceStart() {
        readiness.cancel();
        gameFinished = false;
        broadcast(Component.text("The game was force-started with 3 lives.", NamedTextColor.GOLD));
        startGame();
    }

    // ------------------------------------------------------------------
    // Package-private API used by PillarGamePlayerListener
    // ------------------------------------------------------------------

    MiniGames plugin() {
        return plugin;
    }

    World world() {
        return world;
    }

    GameSettings settings() {
        return settings;
    }

    PlayerLifeManager playerLives() {
        return playerLives;
    }

    PhaseBossBar phaseBossBar() {
        return phaseBossBar;
    }

    boolean isGameFinished() {
        return gameFinished;
    }

    boolean isRoundAlive(Player player) {
        return gameStarted && !gameFinished && isGameWorld(player)
                && playerLives.isAlive(player.getUniqueId());
    }

    void updateSidebar(Player player) {
        if (!isGameWorld(player)) {
            return;
        }
        sidebar.update(player, snapshot());
    }

    void forgetSidebar(Player player) {
        sidebar.remove(player);
    }

    void startVoting() {
        if (world.getPlayers().size() < 2 || readiness.isOpen() || gameStarted || gameFinished) {
            return;
        }
        readiness.start();
    }

    void preparePlayerForLobby(Player player) {
        int slot = arena.assignSlot(player.getUniqueId());
        player.teleport(arena.spawnLocation(slot));
        player.setVelocity(new Vector());
        player.setFallDistance(0.0F);
        updateSidebar(player);
    }

    void sendToPillar(Player player) {
        player.teleport(arena.spawnLocation(arena.assignSlot(player.getUniqueId())));
        player.setVelocity(new Vector());
        player.setFallDistance(0.0F);
    }

    /** Used when a hit would otherwise kill a round-alive player: reassigns them to
     *  whichever pillar is currently least contested instead of their usual one. */
    void sendToNextAvailablePillar(Player player) {
        player.teleport(arena.safestRespawnLocation(player.getUniqueId()));
        player.setVelocity(new Vector());
        player.setFallDistance(0.0F);
    }

    void restoreAfterRespawn(Player player) {
        if (!player.isOnline() || !isGameWorld(player)) {
            return;
        }
        if (!gameStarted && readiness.isOpen()) {
            player.setGameMode(GameMode.SURVIVAL);
            sendToPillar(player);
            jumpFeathers.removeAll(player);
            readiness.addPlayer(player);
            updateSidebar(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (playerLives.consumePendingSpectator(playerId) || playerLives.isEliminated(playerId)) {
            player.setGameMode(GameMode.SPECTATOR);
            sendToPillar(player);
            jumpFeathers.removeAll(player);
            return;
        }
        if (isRoundAlive(player)) {
            player.setGameMode(GameMode.SURVIVAL);
            jumpFeathers.resetState(player);
            jumpFeathers.synchronize(player);
        }
    }

    void consumeLife(Player player, String reason, boolean playerIsDead) {
        if (!isRoundAlive(player) || roundResolved) {
            return;
        }

        int livesLeft = playerLives.consumeLife(player.getUniqueId());
        refreshSidebars();
        if (livesLeft == 0) {
            eliminatePlayer(player, reason, playerIsDead);
            return;
        }

        player.sendActionBar(Component.text("You " + reason + " and have " + livesLeft + " "
                + (livesLeft == 1 ? "life" : "lives") + " left for the game.", NamedTextColor.RED));
        if (!playerIsDead) {
            sendToNextAvailablePillar(player);
            jumpFeathers.resetState(player);
        }
    }

    void eliminatePlayer(Player player, String reason, boolean playerIsDead) {
        UUID playerId = player.getUniqueId();
        if (!playerLives.eliminate(playerId)) {
            return;
        }

        jumpFeathers.removeAll(player);
        jumpFeathers.clearState(player);
        if (playerIsDead) {
            playerLives.markPendingSpectator(playerId);
        } else if (player.isOnline()) {
            player.setGameMode(GameMode.SPECTATOR);
            sendToPillar(player);
        }
        broadcast(Component.text(player.getName() + " " + reason + " and is out for the game.", NamedTextColor.RED));
        checkForGameWinner();
    }

    // ------------------------------------------------------------------
    // Voting -> round/game state machine
    // ------------------------------------------------------------------

    private void startGame() {
        if (gameStarted || gameFinished) {
            return;
        }

        gameStarted = true;
        playerLives.reset();
        replenishPlayers();
        luckyBlocks.start();
        centerLootChest.start();
        beginRound(0);
        gameClockTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tickGameClock, clockPeriodTicks, clockPeriodTicks);
        // The sidebar no longer shows anything sub-second, so it only needs to refresh on
        // state changes (see the explicit refreshSidebars() calls below); only the boss
        // bar's countdown needs this fast, fixed-period timer.
        bossBarTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updatePhaseBossBar, 0L, 2L);
    }

    private void tickGameClock() {
        if (!gameStarted || gameFinished || roundResolved) {
            return;
        }

        checkForGameWinner();
        if (roundResolved) {
            return;
        }

        elapsedRoundTicks += clockPeriodTicks;
        updatePhaseBossBar();
        int itemIntervalTicks = settings.itemIntervalTicksForRound(currentRoundIndex);
        if (elapsedRoundTicks % itemIntervalTicks == 0) {
            for (Player player : roundSurvivors()) {
                items.give(player, "Phase " + (currentRoundIndex + 1));
            }
        }
        refreshSidebars();
        boolean finalPhase = currentRoundIndex == settings.roundCount() - 1;
        if (!finalPhase && elapsedRoundTicks >= settings.roundDurationTicks()) {
            roundResolved = true;
            broadcast(Component.text("Phase " + (currentRoundIndex + 1)
                    + " complete. Advancing to the next phase.", NamedTextColor.YELLOW));
            queueNextRound();
        }
    }

    private void beginRound(int zeroBasedRound) {
        currentRoundIndex = zeroBasedRound;
        elapsedRoundTicks = 0;
        roundStartedAtTick = Bukkit.getCurrentTick();
        roundResolved = false;
        roundTransitionQueued = false;
        updatePhaseBossBar();
        phaseBossBar.showToAll();

        if (zeroBasedRound == 0) {
            // Reached right after playerLives.reset() in startGame(), so every online
            // player starts round 0 as a fresh participant with a full life count.
            for (Player player : List.copyOf(world.getPlayers())) {
                playerLives.addParticipant(player.getUniqueId());
                player.setGameMode(GameMode.SURVIVAL);
                sendToPillar(player);
                jumpFeathers.synchronize(player);
            }
        } else {
            for (Player player : roundSurvivors()) {
                jumpFeathers.synchronize(player);
            }
        }

        int displayedPhase = currentRoundIndex + 1;
        if (displayedPhase == settings.doubleJumpRound()) {
            broadcast(Component.text("Double Jump unlocked! Your feather is fixed in hotbar slot 9.", NamedTextColor.AQUA));
        } else if (displayedPhase == settings.tripleJumpRound()) {
            broadcast(Component.text("Triple Jump unlocked! Your feather is fixed in hotbar slot 9.", NamedTextColor.LIGHT_PURPLE));
        }
        checkForGameWinner();
    }

    private void queueNextRound() {
        if (roundTransitionQueued || gameFinished) {
            return;
        }
        roundTransitionQueued = true;
        plugin.getServer().getScheduler().runTask(plugin, this::advanceRound);
    }

    private void advanceRound() {
        roundTransitionQueued = false;
        if (gameFinished) {
            return;
        }

        int nextRound = currentRoundIndex + 1;
        if (nextRound >= settings.roundCount()) {
            roundResolved = false;
        } else {
            beginRound(nextRound);
        }
    }

    private void finishGame() {
        gameFinished = true;
        gameStarted = false;
        cancelGameClock();
        phaseBossBar.hideFromAll();
        for (Player player : world.getPlayers()) {
            jumpFeathers.synchronize(player);
        }
        jumpFeathers.reset();
        broadcast(Component.text("The match ended with no surviving players. Resetting the arena.",
                NamedTextColor.YELLOW));
        restartGame();
    }

    private void checkForGameWinner() {
        if (roundResolved || !playerLives.hasParticipants()) {
            return;
        }

        List<Player> survivors = roundSurvivors();
        if (survivors.isEmpty()) {
            roundResolved = true;
            broadcast(Component.text("No players have lives remaining. The game is over.", NamedTextColor.YELLOW));
            finishGame();
            return;
        }
        // A solo force-start is useful for testing and has no opponent to eliminate.
        // Only resolve a winner after a match began with at least two participants.
        if (playerLives.participantCount() < 2) {
            return;
        }
        if (survivors.size() != 1) {
            return;
        }

        declareGameWinner(survivors.getFirst());
    }

    private void declareGameWinner(Player winner) {
        if (roundResolved) {
            return;
        }
        roundResolved = true;
        results.recordWin(winner, playerLives.participantIds());
        broadcast(Component.text(winner.getName() + " is the last player alive and wins the game!",
                NamedTextColor.GOLD));
        restartGame();
    }

    private List<Player> roundSurvivors() {
        return world.getPlayers().stream()
                .filter(this::isRoundAlive)
                .toList();
    }

    void restartGame() {
        cancelGameClock();
        readiness.cancel();
        gameStarted = false;
        gameFinished = false;
        currentRoundIndex = -1;
        elapsedRoundTicks = 0;
        roundResolved = false;
        roundTransitionQueued = false;
        playerLives.reset();
        jumpFeathers.reset();
        luckyBlocks.stop();
        centerLootChest.stop();
        arena.clearAssignments();
        phaseBossBar.hideFromAll();

        arena.clearAllNonPillarBlocks();
        for (org.bukkit.entity.Entity entity : List.copyOf(world.getEntities())) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
        arena.ensurePillars();

        for (Player player : world.getPlayers()) {
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            sendToPillar(player);
        }
        startVoting();
    }

    // ------------------------------------------------------------------
    // Small shared helpers
    // ------------------------------------------------------------------

    private void replenishPlayers() {
        for (Player player : world.getPlayers()) {
            player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setExhaustion(0.0F);
            player.setFireTicks(0);
        }
    }

    private int currentJumpTier() {
        if (!gameStarted || gameFinished) {
            return 0;
        }
        if (currentRoundIndex + 1 >= settings.tripleJumpRound()) {
            return 3;
        }
        if (currentRoundIndex + 1 >= settings.doubleJumpRound()) {
            return 2;
        }
        return 0;
    }

    private void cancelGameClock() {
        if (gameClockTask != null) {
            gameClockTask.cancel();
            gameClockTask = null;
        }
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
    }

    private void updatePhaseBossBar() {
        if (!gameStarted || gameFinished || currentRoundIndex < 0) {
            return;
        }
        phaseBossBar.update(currentRoundIndex, Bukkit.getCurrentTick() - roundStartedAtTick);
    }

    private void broadcast(Component message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
        refreshSidebars();
    }

    private void refreshSidebars() {
        sidebar.refreshAll(snapshot());
    }

    private PillarGameSidebarPresenter.RoundSnapshot snapshot() {
        return new PillarGameSidebarPresenter.RoundSnapshot(gameStarted, gameFinished, currentRoundIndex);
    }
}
