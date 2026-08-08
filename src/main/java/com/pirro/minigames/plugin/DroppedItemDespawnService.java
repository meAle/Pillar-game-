package com.pirro.minigames.plugin;

import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;

/** Removes item entities left in the arena after 45 seconds. */
final class DroppedItemDespawnService implements Listener {
    private static final long DESPAWN_DELAY_TICKS = 45L * 20L;

    private final MiniGames plugin;
    private final World world;

    DroppedItemDespawnService(MiniGames plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (!item.getWorld().getUID().equals(world.getUID())) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (item.isValid() && !item.isDead()) {
                item.remove();
            }
        }, DESPAWN_DELAY_TICKS);
    }
}
