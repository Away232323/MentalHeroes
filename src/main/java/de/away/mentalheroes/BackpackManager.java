package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
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
    private final Map<UUID, UUID> wornBackpacks = new HashMap<>();
    private final Map<UUID, ItemDisplay> backpackDisplays =
            new HashMap<>();

    private YamlConfiguration data;
    private BukkitTask visualTask;
    private int validationTicks;

    public BackpackManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "backpack_kind");
        this.idKey = new NamespacedKey(plugin, "backpack_id");
        this.recipeKey = new NamespacedKey(plugin, "backpack");
        this.dataFile = new File(
                plugin.getDataFolder(),
                "backpacks.yml"
        );

        loadData();
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
                lore("Sneak + Right-click: Wear / remove"),
                lore("Other players can open it while worn"),
                Component.text(
                                "Storage: 27 slots",
                                NamedTextColor.DARK_GRAY
                        )
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setItemModel(
                new NamespacedKey(plugin, "backpack")
        );
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

        PersistentDataContainer dataContainer = backpack.getItemMeta()
                .getPersistentDataContainer();
        String rawId = dataContainer.get(
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
        UUID backpackId = ensureBackpackId(item);

        if (player.isSneaking()) {
            toggleWornBackpack(player, backpackId);
        } else {
            openBackpack(player, backpackId);
        }
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

        UUID backpackId = wornBackpacks.get(wearer.getUniqueId());

        if (backpackId == null) {
            return;
        }

        if (findBackpack(wearer, backpackId) == null) {
            clearWornBackpack(wearer, true);
            return;
        }

        event.setCancelled(true);
        openBackpack(event.getPlayer(), backpackId);
    }

    private void toggleWornBackpack(Player player, UUID backpackId) {
        UUID currentId = wornBackpacks.get(player.getUniqueId());

        if (backpackId.equals(currentId)) {
            clearWornBackpack(player, true);
            player.sendActionBar(
                    Component.text(
                            "Backpack removed.",
                            NamedTextColor.GRAY
                    )
            );
            return;
        }

        wornBackpacks.put(player.getUniqueId(), backpackId);
        removeDisplay(player.getUniqueId());
        spawnDisplay(player);
        saveWornState(player.getUniqueId(), backpackId);

        player.sendActionBar(
                Component.text(
                        "Backpack equipped.",
                        NamedTextColor.GREEN
                )
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

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (isBackpack(cursor) || isBackpack(current)) {
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

        boolean reachesBackpack = event.getRawSlots()
                .stream()
                .anyMatch(slot -> slot < BACKPACK_SIZE);

        if (reachesBackpack) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        UUID droppedId = getBackpackId(
                event.getItemDrop().getItemStack()
        );
        UUID wornId = wornBackpacks.get(
                event.getPlayer().getUniqueId()
        );

        if (droppedId != null && droppedId.equals(wornId)) {
            clearWornBackpack(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clearWornBackpack(event.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.discoverRecipe(recipeKey);

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> restoreDisplay(player),
                10L
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeDisplay(event.getPlayer().getUniqueId());
    }

    private void restoreDisplay(Player player) {
        UUID backpackId = wornBackpacks.get(player.getUniqueId());

        if (backpackId == null) {
            return;
        }

        if (findBackpack(player, backpackId) == null) {
            clearWornBackpack(player, true);
            return;
        }

        spawnDisplay(player);
    }

    private ItemStack findBackpack(Player player, UUID backpackId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (backpackId.equals(getBackpackId(item))) {
                return item;
            }
        }

        return null;
    }

    private void updateBackpackDisplays() {
        validationTicks++;
        boolean validateInventory = validationTicks >= 10;

        if (validateInventory) {
            validationTicks = 0;
        }

        boolean wornStateChanged = false;
        Iterator<Map.Entry<UUID, UUID>> iterator =
                wornBackpacks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline()) {
                continue;
            }

            if (validateInventory
                    && findBackpack(player, entry.getValue()) == null) {
                iterator.remove();
                removeDisplay(entry.getKey());
                data.set("worn." + entry.getKey(), null);
                wornStateChanged = true;
                continue;
            }

            updateDisplay(player);
        }

        if (wornStateChanged) {
            saveDataFile();
        }
    }

    private void spawnDisplay(Player player) {
        removeDisplay(player.getUniqueId());

        Location location = backpackLocation(player);
        ItemDisplay display = player.getWorld().spawn(
                location,
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
        display.setTeleportDuration(1);
        display.setInterpolationDuration(1);
        display.setViewRange(1.25F);
        display.setShadowRadius(0.0F);
        display.setTransformation(
                new Transformation(
                        new Vector3f(0.0F, 0.0F, 0.0F),
                        new Quaternionf(),
                        new Vector3f(0.85F, 0.85F, 0.85F),
                        new Quaternionf()
                )
        );

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

        display.teleport(backpackLocation(player));
    }

    private Location backpackLocation(Player player) {
        Location playerLocation = player.getLocation();
        Vector forward = playerLocation.getDirection().setY(0.0D);

        if (forward.lengthSquared() < 0.001D) {
            double yaw = Math.toRadians(playerLocation.getYaw());
            forward = new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        } else {
            forward.normalize();
        }

        double height = player.isSneaking() ? 1.0D : 1.18D;
        Location backpackLocation = playerLocation.clone()
                .add(forward.multiply(-0.31D))
                .add(0.0D, height, 0.0D);

        backpackLocation.setYaw(playerLocation.getYaw());
        backpackLocation.setPitch(0.0F);
        return backpackLocation;
    }

    private void clearWornBackpack(
            Player player,
            boolean save
    ) {
        UUID playerId = player.getUniqueId();

        wornBackpacks.remove(playerId);
        removeDisplay(playerId);

        if (save) {
            saveWornState(playerId, null);
        }
    }

    private void removeDisplay(UUID playerId) {
        ItemDisplay display = backpackDisplays.remove(playerId);

        if (display != null) {
            display.remove();
        }
    }

    private void loadData() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection wornSection =
                data.getConfigurationSection("worn");

        if (wornSection == null) {
            return;
        }

        for (String rawPlayerId : wornSection.getKeys(false)) {
            String rawBackpackId = wornSection.getString(rawPlayerId);

            if (rawBackpackId == null) {
                continue;
            }

            try {
                wornBackpacks.put(
                        UUID.fromString(rawPlayerId),
                        UUID.fromString(rawBackpackId)
                );
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning(
                        "Ignored an invalid backpack UUID in backpacks.yml."
                );
            }
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

    private void saveWornState(UUID playerId, UUID backpackId) {
        data.set(
                "worn." + playerId,
                backpackId == null ? null : backpackId.toString()
        );
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

        data.set("worn", null);

        for (Map.Entry<UUID, UUID> entry : wornBackpacks.entrySet()) {
            data.set(
                    "worn." + entry.getKey(),
                    entry.getValue().toString()
            );
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
