package com.pirro.minigames.plugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Removes dropped items and non-player living entities after 45 seconds. */
final class DroppedItemDespawnService implements Listener {
    private static final int DESPAWN_DELAY_TICKS = 45 * 20;

    private final World world;
    private final Map<UUID, TrackedEntity> trackedEntities = new HashMap<>();
    private final BukkitTask cleanupTask;

    DroppedItemDespawnService(MiniGames plugin, World world) {
        this.world = world;
        this.cleanupTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::removeExpiredEntities, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        track(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof LivingEntity && !(entity instanceof Player)) {
            track(entity);
        }
    }

    void close() {
        cleanupTask.cancel();
        trackedEntities.clear();
    }

    private void track(Entity entity) {
        if (!entity.getWorld().getUID().equals(world.getUID())) {
            return;
        }
        trackedEntities.put(entity.getUniqueId(),
                new TrackedEntity(entity, Bukkit.getCurrentTick() + DESPAWN_DELAY_TICKS));
    }

    private void removeExpiredEntities() {
        int currentTick = Bukkit.getCurrentTick();
        Iterator<TrackedEntity> iterator = trackedEntities.values().iterator();
        while (iterator.hasNext()) {
            TrackedEntity tracked = iterator.next();
            Entity entity = tracked.entity();
            if (!entity.isValid() || entity.isDead()) {
                iterator.remove();
                continue;
            }
            if (currentTick >= tracked.expiresAtTick()) {
                entity.remove();
                iterator.remove();
            }
        }
    }

    private record TrackedEntity(Entity entity, int expiresAtTick) {
    }
}
