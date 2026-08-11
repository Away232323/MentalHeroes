package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
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
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

public final class RestrictedEnchantmentManager implements Listener {

    private static final Set<Enchantment> BLOCKED_ENCHANTMENTS = Set.of(
            Enchantment.MENDING,
            Enchantment.FIRE_ASPECT,
            Enchantment.PUNCH,
            Enchantment.KNOCKBACK,
            Enchantment.THORNS
    );

    private static final Set<Material> BLOCKED_MATERIALS =
            createBlockedMaterials();

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
        event.getLoot().removeIf(this::shouldRemoveItem);

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

        if (shouldRemoveItem(result)) {
            event.setResult(null);
            return;
        }

        if (sanitizeItem(result)) {
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();

        if (shouldRemoveItem(result)) {
            event.setResult(null);
            return;
        }

        if (sanitizeItem(result)) {
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (shouldRemoveItem(event.getInventory().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBrew(BrewEvent event) {
        for (int slot = 0; slot < event.getResults().size(); slot++) {
            ItemStack result = event.getResults().get(slot);

            if (shouldRemoveItem(result)) {
                event.getResults().set(
                        slot,
                        new ItemStack(Material.AIR)
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (shouldRemoveItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            return;
        }

        sanitizeItem(event.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (shouldRemoveItem(event.getEntity().getItemStack())) {
            event.setCancelled(true);
            return;
        }

        sanitizeItem(event.getEntity().getItemStack());
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ItemStack projectileItem = null;

        if (event.getEntity() instanceof AbstractArrow arrow) {
            projectileItem = arrow.getItemStack();
        } else if (event.getEntity() instanceof ThrownPotion potion) {
            projectileItem = potion.getItem();
        }

        if (shouldRemoveItem(projectileItem)) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (shouldRemoveItem(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
            return;
        }

        sanitizeItem(event.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFishing(PlayerFishEvent event) {
        if (event.getCaught() instanceof Item item) {
            if (shouldRemoveItem(item.getItemStack())) {
                item.remove();
                return;
            }

            sanitizeItem(item.getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        event.getDrops().removeIf(this::shouldRemoveItem);

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
                if (shouldRemoveItem(item.getItemStack())) {
                    item.remove();
                    continue;
                }

                sanitizeItem(item.getItemStack());
            } else if (entity instanceof AbstractArrow arrow
                    && shouldRemoveItem(arrow.getItemStack())) {
                arrow.remove();
            } else if (entity instanceof ThrownPotion potion
                    && shouldRemoveItem(potion.getItem())) {
                potion.remove();
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
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);

            if (shouldRemoveItem(item)) {
                inventory.setItem(slot, null);
                continue;
            }

            sanitizeItem(item);
        }
    }

    private void sanitizeEquipment(EntityEquipment equipment) {
        if (equipment == null) {
            return;
        }

        ItemStack[] armor = equipment.getArmorContents();

        for (int slot = 0; slot < armor.length; slot++) {
            if (shouldRemoveItem(armor[slot])) {
                armor[slot] = null;
            } else {
                sanitizeItem(armor[slot]);
            }
        }

        equipment.setArmorContents(armor);

        ItemStack mainHand = equipment.getItemInMainHand();
        ItemStack offHand = equipment.getItemInOffHand();

        if (shouldRemoveItem(mainHand)) {
            mainHand = null;
        } else {
            sanitizeItem(mainHand);
        }

        if (shouldRemoveItem(offHand)) {
            offHand = null;
        } else {
            sanitizeItem(offHand);
        }

        equipment.setItemInMainHand(mainHand);
        equipment.setItemInOffHand(offHand);
    }

    private boolean shouldRemoveItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (BLOCKED_MATERIALS.contains(item.getType())) {
            return true;
        }

        if (isBlockedPotion(item)) {
            return true;
        }

        if (item.getType() != Material.ENCHANTED_BOOK
                || !(item.getItemMeta()
                instanceof EnchantmentStorageMeta storageMeta)) {
            return false;
        }

        return BLOCKED_ENCHANTMENTS.stream()
                .anyMatch(storageMeta::hasStoredEnchant);
    }

    private boolean isBlockedPotion(ItemStack item) {
        Material type = item.getType();
        boolean tippedArrow = type == Material.TIPPED_ARROW;
        boolean healingPotion = type == Material.POTION
                || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION;

        if (!tippedArrow && !healingPotion) {
            return false;
        }

        if (!(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return false;
        }

        if (tippedArrow) {
            return potionMeta.getAllEffects().stream().anyMatch(
                    effect -> effect.getType()
                            .equals(PotionEffectType.POISON)
                            || effect.getType().equals(
                            PotionEffectType.SLOWNESS
                    )
            );
        }

        return potionMeta.getAllEffects().stream().anyMatch(
                effect -> effect.getType().equals(
                        PotionEffectType.INSTANT_HEALTH
                )
        );
    }

    private static Set<Material> createBlockedMaterials() {
        Set<Material> materials = new HashSet<>();
        String[] names = {
                "ENCHANTED_GOLDEN_APPLE",
                "TRIAL_KEY",
                "OMINOUS_TRIAL_KEY",
                "OMINOUS_BOTTLE",
                "HEAVY_CORE",
                "MACE",
                "BREEZE_ROD",
                "WIND_CHARGE",
                "FLOW_ARMOR_TRIM_SMITHING_TEMPLATE",
                "BOLT_ARMOR_TRIM_SMITHING_TEMPLATE",
                "FLOW_BANNER_PATTERN",
                "GUSTER_BANNER_PATTERN",
                "FLOW_POTTERY_SHERD",
                "GUSTER_POTTERY_SHERD",
                "SCRAPE_POTTERY_SHERD",
                "MUSIC_DISC_CREATOR",
                "MUSIC_DISC_CREATOR_MUSIC_BOX",
                "MUSIC_DISC_PRECIPICE",
                "BREEZE_SPAWN_EGG",
                "TRIAL_SPAWNER",
                "VAULT"
        };

        for (String name : names) {
            Material material = Material.matchMaterial(name);

            if (material != null) {
                materials.add(material);
            }
        }

        return Set.copyOf(materials);
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
