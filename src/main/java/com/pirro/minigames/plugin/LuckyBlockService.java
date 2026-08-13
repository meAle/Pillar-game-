package com.pirro.minigames.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the quickly breakable lucky leaf on each pillar and its random reward.
 */
final class LuckyBlockService implements Listener {
    private final MiniGames plugin;
    private final World world;
    private final RandomItemPool items;
    private final Set<BlockPosition> positions;
    private final Map<UUID, Integer> inventoryRewards = new HashMap<>();

    private boolean active;
    private BlockPosition reservedPosition;

    LuckyBlockService(MiniGames plugin, World world, List<Location> locations, RandomItemPool items) {
        this.plugin = plugin;
        this.world = world;
        this.items = items;
        this.positions = locations.stream()
                .map(BlockPosition::of)
                .collect(Collectors.toUnmodifiableSet());
    }

    void start() {
        active = true;
        inventoryRewards.clear();
        items.resetLuckyHistory();
        positions.forEach(this::placeLuckyBlock);
    }

    void stop() {
        active = false;
        inventoryRewards.clear();
        items.resetLuckyHistory();
        for (BlockPosition position : positions) {
            Block block = blockAt(position);
            if (block.getType() == Material.OAK_LEAVES) {
                block.setType(Material.AIR, false);
            }
        }
    }

    /** Temporarily gives one lucky-block position to the match loot chest. */
    void reserveLocation(Location location) {
        BlockPosition position = BlockPosition.of(location);
        if (!positions.contains(position)) {
            throw new IllegalArgumentException("The reserved loot chest location is not a lucky-block pillar.");
        }
        reservedPosition = position;
    }

    void clearReservedLocation() {
        reservedPosition = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockPosition position = BlockPosition.of(block);
        if (!active
                || !block.getWorld().getUID().equals(world.getUID())
                || !positions.contains(position)
                || position.equals(reservedPosition)) {
            return;
        }

        event.setDropItems(false);
        event.setExpToDrop(0);
        int rewardsReceived = inventoryRewards.getOrDefault(event.getPlayer().getUniqueId(), 0);
        if (rewardsReceived < 10) {
            items.giveLucky(event.getPlayer(), "Lucky Block");
            inventoryRewards.put(event.getPlayer().getUniqueId(), rewardsReceived + 1);
        } else {
            items.dropLucky(block.getLocation().add(0.5, 0.5, 0.5), event.getPlayer().getUniqueId());
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (active && blockAt(position).getType().isAir()) {
                placeLuckyBlock(position);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        Block block = event.getBlock();
        if (active
                && block.getWorld().getUID().equals(world.getUID())
                && positions.contains(BlockPosition.of(block))
                && !BlockPosition.of(block).equals(reservedPosition)) {
            event.setCancelled(true);
        }
    }

    private void placeLuckyBlock(BlockPosition position) {
        if (position.equals(reservedPosition)) {
            return;
        }
        Block block = blockAt(position);
        Leaves leaves = (Leaves) Material.OAK_LEAVES.createBlockData();
        leaves.setPersistent(true);
        block.setBlockData(leaves, false);
    }

    private Block blockAt(BlockPosition position) {
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    private record BlockPosition(int x, int y, int z) {
        private static BlockPosition of(Block block) {
            return new BlockPosition(block.getX(), block.getY(), block.getZ());
        }

        private static BlockPosition of(Location location) {
            return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
