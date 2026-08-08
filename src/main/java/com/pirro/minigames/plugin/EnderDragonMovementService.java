package com.pirro.minigames.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Gives spawn-egg-created Ender Dragons a reliable flight path in the arena. */
final class EnderDragonMovementService implements Listener {
    private final MiniGames plugin;
    private final World world;
    private final BooleanSupplier matchActive;
    private final double flightHeight;
    private final double flightRadius;
    private final Set<UUID> controlledDragons = new HashSet<>();

    EnderDragonMovementService(
            MiniGames plugin,
            World world,
            GameSettings settings,
            BooleanSupplier matchActive
    ) {
        this.plugin = plugin;
        this.world = world;
        this.matchActive = matchActive;
        this.flightHeight = settings.pillarTopY() + 16.0;
        this.flightRadius = Math.max(18.0, settings.pillarRadius());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)
                || !matchActive.getAsBoolean()
                || !dragon.getWorld().getUID().equals(world.getUID())
                || !controlledDragons.add(dragon.getUniqueId())) {
            return;
        }

        dragon.setAI(true);
        dragon.setPhase(EnderDragon.Phase.CIRCLING);
        startArenaFlight(dragon);
    }

    private void startArenaFlight(EnderDragon dragon) {
        Location initialLocation = dragon.getLocation();
        new BukkitRunnable() {
            private double angle = Math.atan2(initialLocation.getZ(), initialLocation.getX());

            @Override
            public void run() {
                if (!dragon.isValid() || dragon.isDead() || !matchActive.getAsBoolean()) {
                    controlledDragons.remove(dragon.getUniqueId());
                    cancel();
                    return;
                }

                angle += 0.035;
                double x = Math.cos(angle) * flightRadius;
                double y = flightHeight + Math.sin(angle * 2.0) * 3.0;
                double z = Math.sin(angle) * flightRadius;
                double nextX = Math.cos(angle + 0.035) * flightRadius;
                double nextZ = Math.sin(angle + 0.035) * flightRadius;
                float yaw = (float) Math.toDegrees(Math.atan2(-(nextX - x), nextZ - z));

                dragon.teleport(new Location(world, x, y, z, yaw, 0.0F));
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }
}
