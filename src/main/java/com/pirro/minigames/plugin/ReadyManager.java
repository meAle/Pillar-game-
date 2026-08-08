package com.pirro.minigames.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Owns the pre-game ready toggle, dye button, and two-thirds threshold. */
final class ReadyManager implements Listener {
    private static final int READY_BUTTON_SLOT = 4;

    private final World world;
    private final Runnable refreshSidebars;
    private final Runnable onReadyThresholdReached;
    private final NamespacedKey readyButtonKey;
    private final Set<UUID> readyPlayers = new HashSet<>();

    private boolean open;

    ReadyManager(
            MiniGames plugin,
            World world,
            Runnable refreshSidebars,
            Runnable onReadyThresholdReached
    ) {
        this.world = world;
        this.refreshSidebars = refreshSidebars;
        this.onReadyThresholdReached = onReadyThresholdReached;
        this.readyButtonKey = new NamespacedKey(plugin, "ready_button");
    }

    boolean isOpen() {
        return open;
    }

    void start() {
        if (open) {
            return;
        }
        open = true;
        readyPlayers.clear();
        for (Player player : world.getPlayers()) {
            giveButton(player);
        }
        broadcast(Component.text("Ready check started. Right-click the gray dye or use /ready.",
                NamedTextColor.AQUA));
        broadcastProgress();
    }

    void toggle(Player player) {
        if (!open || !player.getWorld().getUID().equals(world.getUID())) {
            return;
        }

        boolean nowReady;
        if (readyPlayers.add(player.getUniqueId())) {
            nowReady = true;
        } else {
            readyPlayers.remove(player.getUniqueId());
            nowReady = false;
        }
        giveButton(player);
        broadcast(Component.text(player.getName() + (nowReady ? " is ready." : " is no longer ready."),
                nowReady ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        checkThreshold();
    }

    void addPlayer(Player player) {
        readyPlayers.remove(player.getUniqueId());
        if (open) {
            giveButton(player);
            checkThreshold();
        }
    }

    void forgetPlayer(UUID playerId) {
        readyPlayers.remove(playerId);
    }

    void handlePlayerCountChanged() {
        if (!open) {
            return;
        }
        if (world.getPlayers().size() < 2) {
            cancel();
            broadcast(Component.text("Ready check paused until at least 2 players are online.",
                    NamedTextColor.YELLOW));
            return;
        }
        checkThreshold();
    }

    void cancel() {
        open = false;
        readyPlayers.clear();
        for (Player player : world.getPlayers()) {
            removeButton(player);
        }
        refreshSidebars.run();
    }

    int readyCount() {
        return readyPlayers.size();
    }

    int requiredReadyCount() {
        return (2 * world.getPlayers().size() + 2) / 3;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isReadyButton(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        toggle(event.getPlayer());
    }

    private void checkThreshold() {
        if (open && readyCount() >= requiredReadyCount()) {
            cancel();
            broadcast(Component.text("Ready threshold reached. Starting with 3 lives!",
                    NamedTextColor.GOLD));
            onReadyThresholdReached.run();
        } else {
            broadcastProgress();
        }
    }

    private void giveButton(Player player) {
        removeButton(player);
        boolean ready = readyPlayers.contains(player.getUniqueId());
        Material material = ready ? Material.GREEN_DYE : Material.GRAY_DYE;
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Component.text(ready ? "Ready - Right Click to Cancel" : "Not Ready - Right Click",
                ready ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        meta.getPersistentDataContainer().set(readyButtonKey, PersistentDataType.BYTE, (byte) 1);
        button.setItemMeta(meta);
        player.getInventory().setItem(READY_BUTTON_SLOT, button);
    }

    private void removeButton(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isReadyButton(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean isReadyButton(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(readyButtonKey, PersistentDataType.BYTE);
    }

    private void broadcastProgress() {
        broadcast(Component.text(readyCount() + "/" + requiredReadyCount()
                + " players ready (" + world.getPlayers().size() + " online).", NamedTextColor.YELLOW));
    }

    private void broadcast(Component message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
        refreshSidebars.run();
    }
}
