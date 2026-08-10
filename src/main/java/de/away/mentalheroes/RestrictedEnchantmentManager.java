package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

public final class RestrictedEnchantmentManager implements Listener {

    private static final Set<Enchantment> BLOCKED_ENCHANTMENTS = Set.of(
            Enchantment.MENDING,
            Enchantment.FIRE_ASPECT,
            Enchantment.PUNCH,
            Enchantment.KNOCKBACK,
            Enchantment.THORNS
    );

    private final MentalHeroesPlugin plugin;
    private BukkitTask cleanupTask;

    public RestrictedEnchantmentManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::sanitizeOnlinePlayers,
                1L,
                40L
        );
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        EnchantmentOffer[] offers = event.getOffers();

        for (int slot = 0; slot < offers.length; slot++) {
            EnchantmentOffer offer = offers[slot];

            if (offer != null
                    && BLOCKED_ENCHANTMENTS.contains(
                            offer.getEnchantment()
                    )) {
                offers[slot] = null;
            }
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onEnchant(EnchantItemEvent event) {
        event.getEnchantsToAdd().keySet().removeIf(
                BLOCKED_ENCHANTMENTS::contains
        );

        if (event.getEnchantsToAdd().isEmpty()) {
            event.setCancelled(true);
            event.getEnchanter().sendActionBar(Component.text(
                    "That enchantment is disabled.",
                    NamedTextColor.RED
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLootGenerate(LootGenerateEvent event) {
        for (ItemStack item : event.getLoot()) {
            sanitizeItem(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Bukkit.getScheduler().runTask(
                plugin,
                () -> {
                    sanitizeInventory(event.getInventory());

                    if (event.getPlayer() instanceof Player player) {
                        sanitizePlayer(player);
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        scheduleInventoryCleanup(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        scheduleInventoryCleanup(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();

        if (sanitizeItem(result)) {
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();

        if (sanitizeItem(result)) {
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemPickup(EntityPickupItemEvent event) {
        sanitizeItem(event.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDrop(PlayerDropItemEvent event) {
        sanitizeItem(event.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFishing(PlayerFishEvent event) {
        if (event.getCaught() instanceof Item item) {
            sanitizeItem(item.getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        for (ItemStack item : event.getDrops()) {
            sanitizeItem(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(
                plugin,
                () -> sanitizePlayer(event.getPlayer())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Item item) {
                sanitizeItem(item.getItemStack());
            } else if (entity instanceof LivingEntity livingEntity
                    && !(livingEntity instanceof Player)) {
                sanitizeEquipment(livingEntity.getEquipment());
            }
        }
    }

    private void scheduleInventoryCleanup(Inventory inventory) {
        Bukkit.getScheduler().runTask(
                plugin,
                () -> {
                    sanitizeInventory(inventory);

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getOpenInventory().getTopInventory()
                                .equals(inventory)) {
                            sanitizePlayer(player);
                        }
                    }
                }
        );
    }

    private void sanitizeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sanitizePlayer(player);
            sanitizeInventory(
                    player.getOpenInventory().getTopInventory()
            );
        }
    }

    private void sanitizePlayer(Player player) {
        sanitizeInventory(player.getInventory());
        sanitizeInventory(player.getEnderChest());
    }

    private void sanitizeInventory(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            sanitizeItem(item);
        }
    }

    private void sanitizeEquipment(EntityEquipment equipment) {
        if (equipment == null) {
            return;
        }

        ItemStack[] armor = equipment.getArmorContents();

        for (ItemStack item : armor) {
            sanitizeItem(item);
        }

        equipment.setArmorContents(armor);

        ItemStack mainHand = equipment.getItemInMainHand();
        ItemStack offHand = equipment.getItemInOffHand();
        sanitizeItem(mainHand);
        sanitizeItem(offHand);
        equipment.setItemInMainHand(mainHand);
        equipment.setItemInOffHand(offHand);
    }

    private boolean sanitizeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return false;
        }

        boolean changed = false;

        for (Enchantment enchantment : BLOCKED_ENCHANTMENTS) {
            if (meta.hasEnchant(enchantment)) {
                meta.removeEnchant(enchantment);
                changed = true;
            }
        }

        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            for (Enchantment enchantment : BLOCKED_ENCHANTMENTS) {
                if (storageMeta.hasStoredEnchant(enchantment)) {
                    storageMeta.removeStoredEnchant(enchantment);
                    changed = true;
                }
            }
        }

        if (changed) {
            item.setItemMeta(meta);
        }

        return changed;
    }
}
