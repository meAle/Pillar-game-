package com.pirro.minigames.plugin;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The phase/game state machine: who's alive, which phase is active, when a phase
 * ends, and the player-lifecycle plumbing (join/quit/death/respawn) needed
 * to keep that state correct. Terrain, the jump ability, voting, the
 * sidebar, random items, and win persistence are all delegated to their own
 * classes below.
 */
final class PillarGame implements Listener {
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
    private final PillarGameSidebar sidebar;
    private final PhaseBossBar phaseBossBar;
    private final PlayerLifeManager playerLives;
    private final LuckyBlockService luckyBlocks;
    private final CenterLootChestService centerLootChest;
    private final DroppedItemDespawnService droppedItemDespawn;
    private final EnderDragonMovementService enderDragonMovement;

    private BukkitTask gameClockTask;
    private BukkitTask sidebarTask;
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
        this.clockPeriodTicks = calculateClockPeriod(settings);

        this.arena = new PillarArena(world, settings);
        this.items = new RandomItemPool(world, settings.excludedItemKeys());
        this.results = new ResultsStore(plugin);
        this.sidebar = new PillarGameSidebar(world);
        this.phaseBossBar = new PhaseBossBar(world, settings);
        this.playerLives = new PlayerLifeManager(LIVES_PER_GAME);
        this.luckyBlocks = new LuckyBlockService(plugin, world, arena.luckyBlockLocations(), items);
        this.centerLootChest = new CenterLootChestService(world, arena.luckyBlockLocations(), luckyBlocks);
        this.droppedItemDespawn = new DroppedItemDespawnService(plugin, world);
        this.enderDragonMovement = new EnderDragonMovementService(
                plugin, world, settings, () -> gameStarted && !gameFinished);
        this.readiness = new ReadyManager(plugin, world, this::refreshSidebars, this::startGame);
        this.jumpFeathers = new JumpFeatherService(plugin, world, settings, this::isRoundAlive, this::currentJumpTier);
    }

    void initialize() {
        arena.initialize();
        plugin.getLogger().info("Random item pool contains " + items.size() + " items and blocks.");
    }

    /** Listeners other than this one that also need to be registered with Bukkit. */
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
    // Player lifecycle
    // ------------------------------------------------------------------

    @EventHandler
    public void onInitialSpawn(AsyncPlayerSpawnLocationEvent event) {
        UUID playerId = event.getConnection().getProfile().getId();
        if (playerId == null) {
            String name = event.getConnection().getProfile().getName();
            playerId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        }
        event.setSpawnLocation(arena.spawnLocation(arena.assignSlot(playerId)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        preparePlayerForLobby(player);

        if (gameFinished) {
            phaseBossBar.hideFrom(player);
            updateSidebar(player);
            return;
        }
        if (readiness.isOpen()) {
            player.sendMessage(Component.text("Right-click the dye or use /ready when you are ready.",
                    NamedTextColor.AQUA));
            readiness.addPlayer(player);
            readiness.handlePlayerCountChanged();
            updateSidebar(player);
            return;
        }
        if (!gameStarted) {
            startVoting();
            return;
        }

        jumpFeathers.removeAll(player);
        player.setGameMode(GameMode.SPECTATOR);
        phaseBossBar.showTo(player);
        updateSidebar(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isRoundAlive(player)) {
            eliminatePlayer(player, "left the game", false);
        }
        UUID playerId = player.getUniqueId();
        arena.forgetAssignment(playerId);
        readiness.forgetPlayer(playerId);
        plugin.getServer().getScheduler().runTask(plugin, readiness::handlePlayerCountChanged);
        jumpFeathers.clearState(player);
        playerLives.forgetPendingSpectator(playerId);
        sidebar.remove(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isGameWorld(player) && !playerLives.hasParticipant(player.getUniqueId())) {
            return;
        }

        event.setRespawnLocation(arena.safestRespawnLocation(player.getUniqueId()));
        plugin.getServer().getScheduler().runTask(plugin, () -> restoreAfterRespawn(player));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        jumpFeathers.clearState(player);
        event.getDrops().removeIf(jumpFeathers::isJumpFeather);
        if (isRoundAlive(player)) {
            consumeLife(player, "died", true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo().getY() < 0.0 && isRoundAlive(player)) {
            consumeLife(player, "died", false);
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        jumpFeathers.clearState(player);
        if (event.getFrom().getUID().equals(world.getUID()) && !isGameWorld(player)
                && playerLives.hasParticipant(player.getUniqueId())
                && !playerLives.isEliminated(player.getUniqueId())) {
            eliminatePlayer(player, "left the arena", false);
        }
        if (isGameWorld(player)) {
            if (gameStarted && !gameFinished) {
                phaseBossBar.showTo(player);
            } else {
                phaseBossBar.hideFrom(player);
            }
            if (isRoundAlive(player)) {
                jumpFeathers.synchronize(player);
            }
            updateSidebar(player);
        } else {
            phaseBossBar.hideFrom(player);
            jumpFeathers.removeAll(player);
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            sidebar.remove(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID
                || !(event.getEntity() instanceof Player player)
                || !isGameWorld(player)) {
            return;
        }

        if (isRoundAlive(player)) {
            event.setCancelled(true);
            consumeLife(player, "fell into the void", false);
            return;
        }
        if (settings.rescueFromVoid()) {
            event.setCancelled(true);
            sendToPillar(player);
        }
    }

    // ------------------------------------------------------------------
    // Voting -> round/game state machine
    // ------------------------------------------------------------------

    private void startVoting() {
        if (world.getPlayers().size() < 2 || readiness.isOpen() || gameStarted || gameFinished) {
            return;
        }
        readiness.start();
    }

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
        sidebarTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshDisplays, 0L, 2L);
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
            playerLives.clearParticipants();
            for (Player player : List.copyOf(world.getPlayers())) {
                if (playerLives.lives(player.getUniqueId()) < 1
                        && playerLives.hasParticipant(player.getUniqueId())) {
                    if (!player.isDead()) {
                        player.setGameMode(GameMode.SPECTATOR);
                        jumpFeathers.removeAll(player);
                    }
                    continue;
                }
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

    private void consumeLife(Player player, String reason, boolean playerIsDead) {
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
            sendToPillar(player);
            jumpFeathers.resetState(player);
        }
    }

    private void eliminatePlayer(Player player, String reason, boolean playerIsDead) {
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

    private void restoreAfterRespawn(Player player) {
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

    private void preparePlayerForLobby(Player player) {
        int slot = arena.assignSlot(player.getUniqueId());
        player.teleport(arena.spawnLocation(slot));
        player.setVelocity(new Vector());
        player.setFallDistance(0.0F);
        updateSidebar(player);
    }

    private void sendToPillar(Player player) {
        player.teleport(arena.spawnLocation(arena.assignSlot(player.getUniqueId())));
        player.setVelocity(new Vector());
        player.setFallDistance(0.0F);
    }

    private boolean isRoundAlive(Player player) {
        return gameStarted && !gameFinished && isGameWorld(player)
                && playerLives.isAlive(player.getUniqueId());
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
        if (sidebarTask != null) {
            sidebarTask.cancel();
            sidebarTask = null;
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
        sidebar.refreshAll(this::sidebarLines);
    }

    private void refreshDisplays() {
        updatePhaseBossBar();
        refreshSidebars();
    }

    private void updateSidebar(Player player) {
        if (!isGameWorld(player)) {
            return;
        }
        sidebar.update(player, sidebarLines(player));
    }

    private List<String> sidebarLines(Player player) {
        List<String> lines = new ArrayList<>();
        UUID playerId = player.getUniqueId();
        lines.add("Win streak: " + results.getCurrentStreak(playerId));
        lines.add("Best streak: " + results.getBestStreak(playerId));
        ResultsStore.StreakLeader leader = results.getStreakLeader();
        lines.add("Streak leader: " + leader.playerName() + " " + leader.streak());

        if (gameStarted && !gameFinished) {
            int intervalTicks = settings.itemIntervalTicksForRound(currentRoundIndex);
            int elapsedSinceItem = Math.floorMod(Bukkit.getCurrentTick() - roundStartedAtTick, intervalTicks);
            int nextItemTicks = elapsedSinceItem == 0 ? intervalTicks : intervalTicks - elapsedSinceItem;

            lines.add("Phase: " + (currentRoundIndex + 1) + "/" + settings.roundCount());
            lines.add("Lives: " + playerLives.lives(playerId));
            lines.add("Next item: " + formatTenths(nextItemTicks) + "s");
        } else if (readiness.isOpen()) {
            lines.add("Ready: " + readiness.readyCount() + "/" + readiness.requiredReadyCount());
        }
        return lines;
    }

    private static int calculateClockPeriod(GameSettings settings) {
        int period = settings.roundDurationTicks();
        for (int round = 0; round < settings.roundCount(); round++) {
            period = greatestCommonDivisor(period, settings.itemIntervalTicksForRound(round));
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

    private static String formatTenths(int ticks) {
        return BigDecimal.valueOf(ticks)
                .divide(BigDecimal.valueOf(20), 1, RoundingMode.DOWN)
                .toPlainString();
    }

}
