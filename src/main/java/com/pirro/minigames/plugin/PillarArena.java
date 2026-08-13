package com.pirro.minigames.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.util.Vector;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the pillar arena's terrain: pillar placement, the bedrock columns that
 * are protected from breaking, per-player pillar assignment, and the full
 * arena wipe used between matches. Has no idea what round it is or whether a
 * game is running.
 */
final class PillarArena implements Listener {
    private static final int PILLAR_COUNT = 8;
    private static final int[] SLOT_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};
    private static final double SAFE_SPAWN_RADIUS_SQUARED = 10.0 * 10.0;
    // Bounds for the one-time startup sweep in clearLeftoverBlocksFromPreviousSession():
    // generous enough to catch realistic player builds around the pillars, bounded enough
    // to keep the one-off scan fast.
    private static final int STARTUP_WIPE_HORIZONTAL_MARGIN = 32;
    private static final int STARTUP_WIPE_BELOW_MARGIN = 8;
    private static final int STARTUP_WIPE_ABOVE_MARGIN = 48;

    private final World world;
    private final GameSettings settings;
    private final List<Pillar> pillars;
    private final Set<BlockPosition> protectedPillarBlocks = new HashSet<>();
    private final Set<BlockPosition> matchBlocks = new HashSet<>();
    private final Map<UUID, Integer> assignments = new HashMap<>();
    private final Object assignmentLock = new Object();

    PillarArena(World world, GameSettings settings) {
        this.world = world;
        this.settings = settings;
        this.pillars = createPillars(settings.pillarRadius());
    }

    void initialize() {
        ensurePillars();
        clearLeftoverBlocksFromPreviousSession();
        world.setSpawnLocation(spawnLocation(0));
    }

    /**
     * clearAllNonPillarBlocks() only clears blocks tracked via placement events during the
     * current server session - after a server restart that in-memory record is empty even
     * though the world file on disk still has whatever was left standing. This runs once at
     * startup (after ensurePillars() so protectedPillarBlocks is already populated) and scans
     * a bounded region around the pillars instead, so a restart doesn't leave old builds behind.
     */
    private void clearLeftoverBlocksFromPreviousSession() {
        int halfWidth = settings.pillarRadius() + STARTUP_WIPE_HORIZONTAL_MARGIN;
        int bottomY = Math.max(world.getMinHeight(),
                settings.pillarTopY() - settings.bedrockHeight() - STARTUP_WIPE_BELOW_MARGIN);
        int topY = Math.min(world.getMaxHeight() - 1, settings.pillarTopY() + STARTUP_WIPE_ABOVE_MARGIN);

        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfWidth; z <= halfWidth; z++) {
                for (int y = bottomY; y <= topY; y++) {
                    BlockPosition position = new BlockPosition(x, y, z);
                    if (protectedPillarBlocks.contains(position)) {
                        continue;
                    }
                    Block block = blockAt(position);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }

        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }

    void ensurePillars() {
        int pillarBottom = settings.pillarTopY() - settings.bedrockHeight();
        for (Pillar pillar : pillars) {
            world.getChunkAt(pillar.x() >> 4, pillar.z() >> 4).load(true);
            for (int y = pillarBottom; y <= settings.pillarTopY(); y++) {
                BlockPosition position = new BlockPosition(pillar.x(), y, pillar.z());
                protectedPillarBlocks.add(position);
                Block block = blockAt(position);
                if (block.getType() != Material.BEDROCK) {
                    block.setType(Material.BEDROCK, false);
                }
            }
        }
    }

    void clearAllNonPillarBlocks() {
        Set<BlockPosition> positionsToClear = new HashSet<>(matchBlocks);
        for (BlockPosition position : Set.copyOf(matchBlocks)) {
            addLinkedParts(blockAt(position), positionsToClear);
        }

        for (BlockPosition position : positionsToClear) {
            if (!protectedPillarBlocks.contains(position)) {
                Block block = blockAt(position);
                if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        matchBlocks.clear();
    }

    Location spawnLocation(int slot) {
        Pillar pillar = pillars.get(slot);
        Location location = new Location(world, pillar.x() + 0.5, settings.pillarTopY() + 2.0, pillar.z() + 0.5);
        location.setDirection(new Vector(-pillar.x(), 0.0, -pillar.z()));
        return location;
    }

    List<Location> luckyBlockLocations() {
        return pillars.stream()
                .map(pillar -> new Location(world, pillar.x(), settings.pillarTopY() + 1, pillar.z()))
                .toList();
    }

    int assignSlot(UUID playerId) {
        synchronized (assignmentLock) {
            Integer existing = assignments.get(playerId);
            if (existing != null) {
                return existing;
            }
            int[] occupancy = new int[PILLAR_COUNT];
            for (int slot : assignments.values()) {
                occupancy[slot]++;
            }
            int selected = SLOT_ORDER[0];
            for (int slot : SLOT_ORDER) {
                if (occupancy[slot] < occupancy[selected]) {
                    selected = slot;
                }
            }
            assignments.put(playerId, selected);
            return selected;
        }
    }

    void forgetAssignment(UUID playerId) {
        synchronized (assignmentLock) {
            assignments.remove(playerId);
        }
    }

    void clearAssignments() {
        synchronized (assignmentLock) {
            assignments.clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getBlock().getWorld().getUID().equals(world.getUID())) {
            return;
        }
        if (protectedPillarBlocks.contains(BlockPosition.of(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Reassigns a respawning player to the pillar with the fewest nearby living
     * entities. When several pillars are clear, the one furthest from the
     * nearest player or mob wins the tie.
     */
    Location safestRespawnLocation(UUID playerId) {
        List<Entity> otherLivingEntities = world.getEntities().stream()
                .filter(Entity::isValid)
                .filter(entity -> entity instanceof LivingEntity)
                .filter(entity -> !entity.getUniqueId().equals(playerId))
                .toList();

        int safestSlot = SLOT_ORDER[0];
        int fewestNearby = Integer.MAX_VALUE;
        double greatestNearestDistance = -1.0;

        for (int slot : SLOT_ORDER) {
            Location candidate = spawnLocation(slot);
            int nearbyCount = 0;
            double nearestDistance = Double.POSITIVE_INFINITY;

            for (Entity entity : otherLivingEntities) {
                double distanceSquared = entity.getLocation().distanceSquared(candidate);
                if (distanceSquared <= SAFE_SPAWN_RADIUS_SQUARED) {
                    nearbyCount++;
                }
                nearestDistance = Math.min(nearestDistance, distanceSquared);
            }

            if (nearbyCount < fewestNearby
                    || (nearbyCount == fewestNearby && nearestDistance > greatestNearestDistance)) {
                safestSlot = slot;
                fewestNearby = nearbyCount;
                greatestNearestDistance = nearestDistance;
            }
        }

        synchronized (assignmentLock) {
            assignments.put(playerId, safestSlot);
        }
        return spawnLocation(safestSlot);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        trackWithLinkedParts(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        event.getReplacedBlockStates().forEach(state -> trackWithLinkedParts(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        track(event.getBlockClicked().getRelative(event.getBlockFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isGameWorld(event.getBlock())
                && matchBlocks.contains(BlockPosition.of(event.getBlock()))) {
            track(event.getToBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        track(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        track(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        track(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isGameWorld(event.getSource())
                && matchBlocks.contains(BlockPosition.of(event.getSource()))) {
            track(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        track(event.getBlock());
    }

    private void track(Block block) {
        if (!isGameWorld(block)) {
            return;
        }
        BlockPosition position = BlockPosition.of(block);
        if (!protectedPillarBlocks.contains(position)) {
            matchBlocks.add(position);
        }
    }

    private void trackWithLinkedParts(Block block) {
        track(block);
        addLinkedParts(block, matchBlocks);
    }

    private void addLinkedParts(Block block, Set<BlockPosition> positions) {
        if (block.getBlockData() instanceof Bisected bisected) {
            BlockFace otherHalf = bisected.getHalf() == Bisected.Half.BOTTOM
                    ? BlockFace.UP
                    : BlockFace.DOWN;
            addIfUnprotected(block.getRelative(otherHalf), positions);
        }

        if (block.getBlockData() instanceof Bed bed) {
            BlockFace otherPart = bed.getPart() == Bed.Part.FOOT
                    ? bed.getFacing()
                    : bed.getFacing().getOppositeFace();
            addIfUnprotected(block.getRelative(otherPart), positions);
        }
    }

    private void addIfUnprotected(Block block, Set<BlockPosition> positions) {
        if (!isGameWorld(block)) {
            return;
        }
        BlockPosition position = BlockPosition.of(block);
        if (!protectedPillarBlocks.contains(position)) {
            positions.add(position);
        }
    }

    private boolean isGameWorld(Block block) {
        return block.getWorld().getUID().equals(world.getUID());
    }

    private Block blockAt(BlockPosition position) {
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    private static List<Pillar> createPillars(int radius) {
        List<Pillar> result = new ArrayList<>(PILLAR_COUNT);
        for (int index = 0; index < PILLAR_COUNT; index++) {
            double angle = 2.0 * Math.PI * index / PILLAR_COUNT;
            result.add(new Pillar((int) Math.round(Math.cos(angle) * radius),
                    (int) Math.round(Math.sin(angle) * radius)));
        }
        return List.copyOf(result);
    }

    private record Pillar(int x, int z) {
    }

    private record BlockPosition(int x, int y, int z) {
        private static BlockPosition of(Block block) {
            return new BlockPosition(block.getX(), block.getY(), block.getZ());
        }
    }
}
