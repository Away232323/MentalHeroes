package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BackpackManager implements Listener {

    private static final int BACKPACK_SIZE = 27;
    private static final String ITEM_KIND_BACKPACK = "backpack";

    private final MentalHeroesPlugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey idKey;
    private final NamespacedKey recipeKey;
    private final File dataFile;

    private final Map<UUID, Inventory> backpackInventories =
            new HashMap<>();
    private final Map<UUID, UUID> visibleBackpacks = new HashMap<>();
    private final Map<UUID, ItemDisplay> backpackDisplays =
            new HashMap<>();

    private YamlConfiguration data;
    private BukkitTask visualTask;

    public BackpackManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "backpack_kind");
        this.idKey = new NamespacedKey(plugin, "backpack_id");
        this.recipeKey = new NamespacedKey(plugin, "backpack");
        this.dataFile = new File(
                plugin.getDataFolder(),
                "backpacks.yml"
        );
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                recipeKey,
                createBackpackTemplate()
        );

        recipe.shape(
                "WLW",
                "LBL",
                "WLW"
        );
        recipe.setIngredient('W', Material.WHITE_WOOL);
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('B', Material.CHEST);

        plugin.getServer().addRecipe(recipe);
    }

    public void start() {
        visualTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::updateBackpackDisplays,
                1L,
                1L
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(recipeKey);
        }
    }

    public void stop() {
        if (visualTask != null) {
            visualTask.cancel();
            visualTask = null;
        }

        for (ItemDisplay display : backpackDisplays.values()) {
            display.remove();
        }

        backpackDisplays.clear();
        visibleBackpacks.clear();
        saveAll();
    }

    private ItemStack createBackpackTemplate() {
        ItemStack backpack = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = backpack.getItemMeta();

        meta.itemName(
                Component.text("Backpack", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false)
        );
        meta.lore(List.of(
                lore("Right-click: Open backpack"),
                lore("Automatically worn while in your inventory"),
                lore("Other players can open it from your back"),
                Component.text(
                                "Storage: 27 slots",
                                NamedTextColor.DARK_GRAY
                        )
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setItemModel(new NamespacedKey(plugin, "backpack"));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(
                kindKey,
                PersistentDataType.STRING,
                ITEM_KIND_BACKPACK
        );

        backpack.setItemMeta(meta);
        return backpack;
    }

    private Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private boolean isBackpack(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String kind = item.getItemMeta()
                .getPersistentDataContainer()
                .get(kindKey, PersistentDataType.STRING);

        return ITEM_KIND_BACKPACK.equals(kind);
    }

    private UUID ensureBackpackId(ItemStack backpack) {
        UUID existingId = getBackpackId(backpack);

        if (existingId != null) {
            return existingId;
        }

        UUID newId = UUID.randomUUID();
        ItemMeta meta = backpack.getItemMeta();

        meta.getPersistentDataContainer().set(
                idKey,
                PersistentDataType.STRING,
                newId.toString()
        );
        backpack.setItemMeta(meta);
        return newId;
    }

    private UUID getBackpackId(ItemStack backpack) {
        if (!isBackpack(backpack)) {
            return null;
        }

        PersistentDataContainer container = backpack.getItemMeta()
                .getPersistentDataContainer();
        String rawId = container.get(
                idKey,
                PersistentDataType.STRING
        );

        if (rawId == null) {
            return null;
        }

        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = false
    )
    public void onBackpackUse(PlayerInteractEvent event) {
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        EquipmentSlot hand = event.getHand();

        if (hand == null) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();

        if (!isBackpack(item)) {
            return;
        }

        event.setCancelled(true);
        openBackpack(player, ensureBackpackId(item));
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onPlayerRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Player wearer)) {
            return;
        }

        ItemStack backpack = findBackpack(wearer);

        if (backpack == null) {
            return;
        }

        event.setCancelled(true);
        openBackpack(
                event.getPlayer(),
                ensureBackpackId(backpack)
        );
    }

    private void openBackpack(Player viewer, UUID backpackId) {
        viewer.openInventory(
                backpackInventories.computeIfAbsent(
                        backpackId,
                        this::loadInventory
                )
        );
    }

    private Inventory loadInventory(UUID backpackId) {
        BackpackHolder holder = new BackpackHolder(backpackId);
        Inventory inventory = Bukkit.createInventory(
                holder,
                BACKPACK_SIZE,
                Component.text("Backpack", NamedTextColor.GOLD)
        );
        holder.setInventory(inventory);

        String basePath = "backpacks."
                + backpackId
                + ".items.";

        for (int slot = 0; slot < BACKPACK_SIZE; slot++) {
            ItemStack item = data.getItemStack(basePath + slot);

            if (item != null) {
                inventory.setItem(slot, item);
            }
        }

        return inventory;
    }

    @EventHandler
    public void onBackpackClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder()
                instanceof BackpackHolder holder) {
            saveInventory(holder.backpackId(), event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBackpackClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof BackpackHolder)) {
            return;
        }

        if (isBackpack(event.getCursor())
                || isBackpack(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBackpackDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof BackpackHolder)
                || !isBackpack(event.getOldCursor())) {
            return;
        }

        if (event.getRawSlots().stream()
                .anyMatch(slot -> slot < BACKPACK_SIZE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)
                || !keyed.getKey().equals(recipeKey)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.isShiftClick()
                || findBackpack(player) != null
                || isBackpack(event.getCursor())) {
            event.setCancelled(true);
            player.sendActionBar(
                    Component.text(
                            "You can only carry one backpack.",
                            NamedTextColor.RED
                    )
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !isBackpack(event.getItem().getItemStack())
                || findBackpack(player) == null) {
            return;
        }

        event.setCancelled(true);
        player.sendActionBar(
                Component.text(
                        "You can only carry one backpack.",
                        NamedTextColor.RED
                )
        );
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isBackpack(event.getItemDrop().getItemStack())) {
            hideBackpack(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        hideBackpack(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(recipeKey);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideBackpack(event.getPlayer().getUniqueId());
    }

    private ItemStack findBackpack(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isBackpack(item)) {
                return item;
            }
        }

        return null;
    }

    private void updateBackpackDisplays() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack backpack = findBackpack(player);

            if (backpack == null || player.isDead()) {
                hideBackpack(player.getUniqueId());
                continue;
            }

            UUID backpackId = ensureBackpackId(backpack);
            UUID visibleId = visibleBackpacks.get(player.getUniqueId());

            if (!backpackId.equals(visibleId)) {
                visibleBackpacks.put(player.getUniqueId(), backpackId);
                spawnDisplay(player);
            } else {
                updateDisplay(player);
            }

            dropExtraBackpacks(player);
        }
    }

    private void dropExtraBackpacks(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean keptBackpack = false;
        boolean droppedExtra = false;

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];

            if (!isBackpack(item)) {
                continue;
            }

            if (!keptBackpack) {
                keptBackpack = true;
                continue;
            }

            player.getInventory().setItem(slot, null);
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    item
            );
            droppedExtra = true;
        }

        if (droppedExtra) {
            player.sendActionBar(
                    Component.text(
                            "You can only carry one backpack.",
                            NamedTextColor.RED
                    )
            );
        }
    }

    private void spawnDisplay(Player player) {
        removeDisplay(player.getUniqueId());

        ItemDisplay display = player.getWorld().spawn(
                player.getLocation(),
                ItemDisplay.class
        );

        display.setItemStack(createBackpackTemplate());
        display.setItemDisplayTransform(
                ItemDisplay.ItemDisplayTransform.FIXED
        );
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setTeleportDuration(0);
        display.setInterpolationDuration(1);
        display.setViewRange(1.25F);
        display.setShadowRadius(0.0F);
        display.setTransformation(
                new Transformation(
                        new Vector3f(0.0F, -0.62F, -0.42F),
                        new Quaternionf(),
                        new Vector3f(0.48F, 0.48F, 0.48F),
                        new Quaternionf()
                )
        );
        display.setRotation(player.getLocation().getYaw(), 0.0F);

        player.addPassenger(display);
        player.hideEntity(plugin, display);

        backpackDisplays.put(player.getUniqueId(), display);
    }

    private void updateDisplay(Player player) {
        ItemDisplay display = backpackDisplays.get(
                player.getUniqueId()
        );

        if (display == null
                || !display.isValid()
                || !display.getWorld().equals(player.getWorld())) {
            spawnDisplay(player);
            return;
        }

        if (!player.getPassengers().contains(display)) {
            player.addPassenger(display);
        }

        display.setRotation(player.getLocation().getYaw(), 0.0F);
        player.hideEntity(plugin, display);
    }

    private void hideBackpack(UUID playerId) {
        visibleBackpacks.remove(playerId);
        removeDisplay(playerId);
    }

    private void removeDisplay(UUID playerId) {
        ItemDisplay display = backpackDisplays.remove(playerId);

        if (display != null) {
            display.remove();
        }
    }

    private void saveInventory(UUID backpackId, Inventory inventory) {
        String basePath = "backpacks."
                + backpackId
                + ".items";

        data.set(basePath, null);

        for (int slot = 0; slot < BACKPACK_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);

            if (item != null && !item.getType().isAir()) {
                data.set(basePath + "." + slot, item);
            }
        }

        saveDataFile();
    }

    private void saveAll() {
        for (Map.Entry<UUID, Inventory> entry
                : backpackInventories.entrySet()) {
            String basePath = "backpacks."
                    + entry.getKey()
                    + ".items";

            data.set(basePath, null);

            for (int slot = 0; slot < BACKPACK_SIZE; slot++) {
                ItemStack item = entry.getValue().getItem(slot);

                if (item != null && !item.getType().isAir()) {
                    data.set(basePath + "." + slot, item);
                }
            }
        }

        saveDataFile();
    }

    private void saveDataFile() {
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "The backpacks.yml file could not be saved!"
            );
            exception.printStackTrace();
        }
    }

    private static final class BackpackHolder
            implements InventoryHolder {

        private final UUID backpackId;
        private Inventory inventory;

        private BackpackHolder(UUID backpackId) {
            this.backpackId = backpackId;
        }

        private UUID backpackId() {
            return backpackId;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
