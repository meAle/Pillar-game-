package com.pirro.minigames.plugin;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Owns the Double/Triple Jump feather: the item itself, its hotbar-9 pinning,
 * air-jump bookkeeping, and every event needed to stop it from being dropped,
 * traded, or crafted away. Round/eligibility state is supplied by the caller
 * (aliveCheck, currentTier) rather than tracked here, so this class has no
 * idea what round it is.
 */
final class JumpFeatherService implements Listener {
    private static final int JUMP_FEATHER_SLOT = 8;

    private final World world;
    private final GameSettings settings;
    private final Predicate<Player> aliveCheck;
    private final IntSupplier currentTier;
    private final NamespacedKey jumpFeatherKey;
    private final NamespacedKey jumpFeatherTierKey;
    private final NamespacedKey jumpCooldownKey;
    private final Map<UUID, JumpState> jumpStates = new HashMap<>();

    JumpFeatherService(MiniGames plugin, World world, GameSettings settings,
                       Predicate<Player> aliveCheck, IntSupplier currentTier) {
        this.world = world;
        this.settings = settings;
        this.aliveCheck = aliveCheck;
        this.currentTier = currentTier;
        this.jumpFeatherKey = new NamespacedKey(plugin, "jump_feather");
        this.jumpFeatherTierKey = new NamespacedKey(plugin, "jump_feather_tier");
        this.jumpCooldownKey = new NamespacedKey(plugin, "jump_feather_cooldown");
    }

    void reset() {
        jumpStates.clear();
    }

    /** Gives/removes the feather to match the current round's unlocked tier. */
    void synchronize(Player player) {
        if (currentTier.getAsInt() < 2 || !aliveCheck.test(player)) {
            removeAll(player);
            jumpStates.remove(player.getUniqueId());
            return;
        }
        giveFeather(player, currentTier.getAsInt());
    }

    /** Admin/manual give, independent of whichever tier the round has unlocked. */
    void giveFeather(Player player, int jumpTier) {
        PlayerInventory inventory = player.getInventory();
        ItemStack displaced = inventory.getItem(JUMP_FEATHER_SLOT);
        boolean targetWasFeather = isJumpFeather(displaced);
        removeAll(player);
        inventory.setItem(JUMP_FEATHER_SLOT, createJumpFeather(jumpTier));
        if (!targetWasFeather && displaced != null && !displaced.isEmpty()) {
            preserveDisplacedItem(player, displaced);
        }
        resetState(player);
    }

    void removeAll(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isJumpFeather(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
    }

    void clearState(Player player) {
        jumpStates.remove(player.getUniqueId());
    }

    void resetState(Player player) {
        if (availableJumpTier(player) < 2 || !aliveCheck.test(player)) {
            jumpStates.remove(player.getUniqueId());
            return;
        }
        jumpStates.put(player.getUniqueId(), newJumpState(player));
    }

    boolean hasFeather(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isJumpFeather(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    boolean isJumpFeather(ItemStack item) {
        return item != null && item.getType() == Material.FEATHER && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(jumpFeatherKey, PersistentDataType.BOOLEAN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        if (!isJumpAbilityActive(player)) {
            return;
        }
        JumpState state = jumpStates.computeIfAbsent(player.getUniqueId(), ignored -> newJumpState(player));
        state.airJumpsLeft = maximumAirJumps(player);
        state.airborneCycle = true;
        state.seenOffGround = false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isJumpAbilityActive(player)) {
            return;
        }
        JumpState state = jumpStates.computeIfAbsent(player.getUniqueId(), ignored -> newJumpState(player));
        if (!isGrounded(player)) {
            state.airborneCycle = true;
            state.seenOffGround = true;
            return;
        }
        if (state.airborneCycle && state.seenOffGround) {
            state.airJumpsLeft = maximumAirJumps(player);
            state.airborneCycle = false;
            state.seenOffGround = false;
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isJumpFeather(event.getItem())) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        activateJump(event.getPlayer(), event.getItem());
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (isJumpFeather(item)) {
            event.setCancelled(true);
            activateJump(event.getPlayer(), item);
        }
    }

    @EventHandler
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (isJumpFeather(item)) {
            event.setCancelled(true);
            activateJump(event.getPlayer(), item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropJumpFeather(PlayerDropItemEvent event) {
        if (!isJumpFeather(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("The jump feather cannot be dropped.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJumpFeatherInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean touchesJumpFeather = isJumpFeather(event.getCurrentItem()) || isJumpFeather(event.getCursor());
        if (!touchesJumpFeather && event.getHotbarButton() >= 0) {
            touchesJumpFeather = isJumpFeather(player.getInventory().getItem(event.getHotbarButton()));
        }
        if (!touchesJumpFeather && event.getClick() == ClickType.SWAP_OFFHAND) {
            touchesJumpFeather = isJumpFeather(player.getInventory().getItemInOffHand());
        }
        if (touchesJumpFeather) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJumpFeatherInventoryDrag(InventoryDragEvent event) {
        if (isJumpFeather(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapJumpFeather(PlayerSwapHandItemsEvent event) {
        if (isJumpFeather(event.getMainHandItem()) || isJumpFeather(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJumpFeatherRecipeBook(PlayerRecipeBookClickEvent event) {
        if (hasFeather(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(
                    "Recipe-book autofill is disabled while the jump feather is active.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftWithJumpFeather(CraftItemEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (!isJumpFeather(ingredient)) {
                continue;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendActionBar(Component.text("The jump feather cannot be used for crafting.", NamedTextColor.RED));
            }
            return;
        }
    }

    private void activateJump(Player player, ItemStack feather) {
        if (!isJumpAbilityActive(player)) {
            player.sendActionBar(Component.text("This feather is not active yet.", NamedTextColor.RED));
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isFlying() || player.isGliding()
                || player.isInsideVehicle() || player.isClimbing() || player.isSwimming()) {
            return;
        }
        if (player.hasCooldown(feather)) {
            return;
        }

        JumpState state = jumpStates.computeIfAbsent(player.getUniqueId(), ignored -> newJumpState(player));
        boolean grounded = isGrounded(player);
        if (grounded && state.airborneCycle && state.seenOffGround) {
            state.airJumpsLeft = maximumAirJumps(player);
            state.airborneCycle = false;
            state.seenOffGround = false;
        }
        if (!grounded || state.airborneCycle) {
            if (state.airJumpsLeft <= 0) {
                player.sendActionBar(Component.text("No jumps left - land to recharge.", NamedTextColor.RED));
                applyJumpCooldown(player, feather);
                return;
            }
            state.airJumpsLeft--;
        } else {
            state.airJumpsLeft = maximumAirJumps(player);
            state.airborneCycle = true;
            state.seenOffGround = false;
        }

        Vector forward = player.getLocation().getDirection().setY(0.0);
        if (forward.lengthSquared() > 0.0001) {
            forward.normalize().multiply(settings.jumpForwardVelocity());
        }
        Vector velocity = player.getVelocity();
        velocity.setX(forward.getX());
        velocity.setY(settings.jumpVelocity());
        velocity.setZ(forward.getZ());
        player.setVelocity(velocity);
        player.setFallDistance(0.0F);
        applyJumpCooldown(player, feather);
    }

    private void applyJumpCooldown(Player player, ItemStack feather) {
        player.setCooldown(feather, settings.jumpClickCooldownTicks());
    }

    private void preserveDisplacedItem(Player player, ItemStack displaced) {
        PlayerInventory inventory = player.getInventory();
        int emptySlot = inventory.firstEmpty();
        if (emptySlot >= 0) {
            inventory.setItem(emptySlot, displaced);
            return;
        }

        Item dropped = world.dropItem(player.getEyeLocation(), displaced);
        dropped.setVelocity(new Vector());
        dropped.setGravity(false);
        dropped.setInvulnerable(true);
        dropped.setFireTicks(0);
        dropped.setWillAge(false);
        dropped.setOwner(player.getUniqueId());
        player.sendActionBar(Component.text("Hotbar slot 9 is reserved for the jump feather. Its previous item is "
                + "floating beside you.", NamedTextColor.YELLOW));
    }

    private ItemStack createJumpFeather(int jumpTier) {
        ItemStack feather = ItemStack.of(Material.FEATHER);
        feather.editMeta(meta -> configureJumpFeatherMeta(meta, jumpTier));
        return feather;
    }

    private void configureJumpFeatherMeta(ItemMeta meta, int jumpTier) {
        boolean tripleJump = jumpTier >= 3;
        NamedTextColor color = tripleJump ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
        meta.itemName(Component.text(tripleJump ? "Triple Jump" : "Double Jump", color)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to leap forward.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(tripleJump ? "Two extra jumps while airborne." : "One extra jump while airborne.", color)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(jumpFeatherKey, PersistentDataType.BOOLEAN, true);
        meta.getPersistentDataContainer().set(jumpFeatherTierKey, PersistentDataType.INTEGER, jumpTier);
        UseCooldownComponent cooldown = meta.getUseCooldown();
        cooldown.setCooldownSeconds(settings.jumpClickCooldownTicks() / 20.0F);
        cooldown.setCooldownGroup(jumpCooldownKey);
        meta.setUseCooldown(cooldown);
    }

    private int jumpFeatherTier(ItemStack item) {
        if (!isJumpFeather(item)) {
            return 0;
        }
        Integer tier = item.getItemMeta().getPersistentDataContainer()
                .get(jumpFeatherTierKey, PersistentDataType.INTEGER);
        return tier != null && tier >= 2 && tier <= 3 ? tier : 0;
    }

    private boolean isJumpAbilityActive(Player player) {
        return availableJumpTier(player) >= 2 && aliveCheck.test(player) && !player.isDead();
    }

    private int availableJumpTier(Player player) {
        return jumpFeatherTier(player.getInventory().getItem(JUMP_FEATHER_SLOT));
    }

    private int maximumAirJumps(Player player) {
        return Math.max(0, availableJumpTier(player) - 1);
    }

    private JumpState newJumpState(Player player) {
        boolean grounded = isGrounded(player);
        return new JumpState(maximumAirJumps(player), !grounded, !grounded);
    }

    private boolean isGrounded(Player player) {
        BoundingBox bounds = player.getBoundingBox();
        BoundingBox footProbe = new BoundingBox(
                bounds.getMinX() + 0.05,
                bounds.getMinY() - 0.08,
                bounds.getMinZ() + 0.05,
                bounds.getMaxX() - 0.05,
                bounds.getMinY() + 0.01,
                bounds.getMaxZ() - 0.05
        );
        return player.wouldCollideUsing(footProbe);
    }

    private static final class JumpState {
        private int airJumpsLeft;
        private boolean airborneCycle;
        private boolean seenOffGround;

        private JumpState(int airJumpsLeft, boolean airborneCycle, boolean seenOffGround) {
            this.airJumpsLeft = airJumpsLeft;
            this.airborneCycle = airborneCycle;
            this.seenOffGround = seenOffGround;
        }
    }
}