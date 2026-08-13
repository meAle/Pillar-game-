package com.pirro.minigames.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Creates and fills a protected loot chest on one random pillar per match.
 */
final class CenterLootChestService implements Listener {
    private final World world;
    private final List<Location> pillarLocations;
    private final LuckyBlockService luckyBlocks;

    private boolean active;
    private BlockPosition chestPosition;

    CenterLootChestService(World world, List<Location> pillarLocations, LuckyBlockService luckyBlocks) {
        this.world = world;
        this.pillarLocations = List.copyOf(pillarLocations);
        this.luckyBlocks = luckyBlocks;
        if (this.pillarLocations.isEmpty()) {
            throw new IllegalArgumentException("At least one pillar location is required for the loot chest.");
        }
    }

    void start() {
        active = true;
        List<Location> unoccupied = pillarLocations.stream().filter(this::isUnoccupied).toList();
        List<Location> candidates = unoccupied.isEmpty() ? pillarLocations : unoccupied;
        Location selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        chestPosition = BlockPosition.of(selected);
        luckyBlocks.reserveLocation(selected);
        Block chestBlock = blockAt(chestPosition);
        chestBlock.setType(Material.CHEST, false);
        if (chestBlock.getState() instanceof Chest chest) {
            fill(chest.getBlockInventory());
        }
    }

    /** True when no player is currently standing on the pillar at this location. */
    private boolean isUnoccupied(Location pillarLocation) {
        for (Player player : world.getPlayers()) {
            if (player.getLocation().getBlockX() == pillarLocation.getBlockX()
                    && player.getLocation().getBlockZ() == pillarLocation.getBlockZ()) {
                return false;
            }
        }
        return true;
    }

    void stop() {
        active = false;
        if (chestPosition == null) {
            luckyBlocks.clearReservedLocation();
            return;
        }
        Block chestBlock = blockAt(chestPosition);
        if (chestBlock.getState() instanceof Chest chest) {
            chest.getBlockInventory().clear();
        }
        chestBlock.setType(Material.AIR, false);
        chestPosition = null;
        luckyBlocks.clearReservedLocation();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (active && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    private void fill(Inventory inventory) {
        inventory.clear();
        List<ItemStack> combatLoot = new ArrayList<>(List.of(
                new ItemStack(Material.DIAMOND_SWORD),
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.SHIELD),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.DIAMOND_BOOTS),
                new ItemStack(Material.GOLDEN_APPLE, 3),
                new ItemStack(Material.ENDER_PEARL, 4),
                new ItemStack(Material.ARROW, 32),
                new ItemStack(Material.COBWEB, 8)
        ));
        combatLoot.forEach(EquipmentEnchantments::applyMaximumCompatibleEnchantments);
        Collections.shuffle(combatLoot, ThreadLocalRandom.current());

        List<ItemStack> blockStacks = new ArrayList<>(List.of(
                new ItemStack(Material.COBBLESTONE),
                new ItemStack(Material.OAK_PLANKS),
                new ItemStack(Material.STONE_BRICKS),
                new ItemStack(Material.DEEPSLATE_TILES),
                new ItemStack(Material.BRICKS),
                new ItemStack(Material.GLASS)
        ));
        Collections.shuffle(blockStacks, ThreadLocalRandom.current());

        List<Integer> slots = new ArrayList<>(IntStream.range(0, inventory.getSize()).boxed().toList());
        Collections.shuffle(slots, ThreadLocalRandom.current());
        for (ItemStack item : combatLoot.subList(0, 7)) {
            inventory.setItem(slots.removeLast(), item);
        }
        int blockTypeCount = ThreadLocalRandom.current().nextInt(2, 5);
        int blocksRemaining = ThreadLocalRandom.current().nextInt(32, 65);
        for (int index = 0; index < blockTypeCount; index++) {
            ItemStack blocks = blockStacks.get(index);
            int typesRemaining = blockTypeCount - index;
            int amount = typesRemaining == 1
                    ? blocksRemaining
                    : ThreadLocalRandom.current().nextInt(
                    4,
                    blocksRemaining - 4 * (typesRemaining - 1) + 1
            );
            blocks.setAmount(amount);
            blocksRemaining -= amount;
            inventory.setItem(slots.removeLast(), blocks);
        }
    }

    private boolean isProtected(Block block) {
        if (!block.getWorld().getUID().equals(world.getUID())) {
            return false;
        }
        BlockPosition position = BlockPosition.of(block);
        return position.equals(chestPosition);
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
