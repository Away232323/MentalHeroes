package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MentalHeroes-only player graves.
 *
 * Behaviour:
 * - Only deaths inside the configured MentalHeroes world create a grave.
 * - The grave is the dead player's head with a floating name/timer/death-cause display.
 * - Anyone may right-click and loot it.
 * - Sneak-right-click never performs an instant "take all" action; it just opens the grave.
 * - Breaking the grave never drops the head. Instead all remaining stored items drop normally.
 * - After one hour the grave disappears and all remaining items are dropped on the ground.
 * - Graves are persisted through restarts so a reboot cannot delete the loot or reset the timer.
 */
public final class GraveManager implements Listener {

    private static final long LIFETIME_MILLIS = 60L * 60L * 1000L;
    private static final int INVENTORY_SIZE = 54;

    private final MentalHeroesPlugin plugin;
    private final File dataFile;
    private final NamespacedKey graveKey;
    private final Map<UUID, Grave> graves = new HashMap<>();
    private final Map<BlockKey, UUID> gravesByBlock = new HashMap<>();

    private BukkitTask updateTask;
    private BukkitTask saveTask;

    public GraveManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "graves.yml");
        this.graveKey = new NamespacedKey(plugin, "hero_grave_id");
    }

    public void start() {
        load();
        restoreLoadedGraves();

        updateTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                20L
        );
        saveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::save,
                600L,
                600L
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        save();
        for (Grave grave : new ArrayList<>(graves.values())) {
            removeDisplay(grave);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isHeroesWorld(player) || event.getKeepInventory()) {
            return;
        }

        List<ItemStack> drops = event.getDrops().stream()
                .filter(item -> item != null && !item.getType().isAir())
                .map(ItemStack::clone)
                .toList();

        if (drops.isEmpty()) {
            return;
        }

        Location graveLocation = findGraveLocation(player.getLocation());
        if (graveLocation == null) {
            // Never delete loot when there is no safe place for a grave.
            return;
        }

        Grave grave = createGrave(
                player,
                graveLocation,
                describeDeath(player),
                drops,
                System.currentTimeMillis() + LIFETIME_MILLIS
        );

        if (grave == null) {
            return;
        }

        event.getDrops().clear();
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !plugin.isHeroesWorld(block.getWorld())) {
            return;
        }

        Grave grave = graveAt(block);
        if (grave == null) {
            return;
        }

        // Right-click and sneak-right-click deliberately do the exact same thing:
        // open the inventory. There is no AxGraves-style instant "take all" shortcut.
        if (!event.getAction().isRightClick()) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().openInventory(grave.inventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Grave grave = graveAt(event.getBlock());
        if (grave == null) {
            return;
        }

        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);

        // The player head itself is never an obtainable item.
        destroy(grave, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GraveHolder holder)) {
            return;
        }

        Grave grave = graves.get(holder.graveId());
        if (grave == null) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean clickedTop = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;

        // Grave inventories are loot-only. Players may take items out, but cannot
        // use graves as free storage by inserting their own items.
        if (!clickedTop && event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (clickedTop) {
            switch (event.getAction()) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME,
                     SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD,
                     CLONE_STACK -> event.setCancelled(true);
                default -> {
                    // Pickup actions and shift-clicking FROM the grave are allowed.
                }
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Grave current = graves.get(holder.graveId());
            if (current != null && isEmpty(current.inventory())) {
                destroy(current, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GraveHolder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GraveHolder holder)) {
            return;
        }

        Grave grave = graves.get(holder.graveId());
        if (grave == null) {
            return;
        }

        if (isEmpty(grave.inventory())) {
            destroy(grave, false);
        } else {
            save();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> graveAt(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> graveAt(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> graveAt(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> graveAt(block) != null)) {
            event.setCancelled(true);
        }
    }

    private Grave createGrave(
            Player owner,
            Location location,
            String deathCause,
            List<ItemStack> items,
            long expiresAt
    ) {
        UUID id = UUID.randomUUID();
        Inventory inventory = createInventory(id, owner.getName());
        Map<Integer, ItemStack> overflow = inventory.addItem(
                items.toArray(ItemStack[]::new)
        );

        Block block = location.getBlock();
        if (!block.getType().isAir()) {
            return null;
        }

        block.setType(Material.PLAYER_HEAD, false);
        if (!(block.getState() instanceof Skull skull)) {
            block.setType(Material.AIR, false);
            return null;
        }

        skull.setOwningPlayer(owner);
        skull.getPersistentDataContainer().set(
                graveKey,
                org.bukkit.persistence.PersistentDataType.STRING,
                id.toString()
        );
        skull.update(true, false);

        Grave grave = new Grave(
                id,
                owner.getUniqueId(),
                owner.getName(),
                block.getLocation(),
                deathCause,
                expiresAt,
                inventory,
                null
        );

        graves.put(id, grave);
        gravesByBlock.put(BlockKey.of(block), id);
        spawnDisplay(grave);

        if (!overflow.isEmpty()) {
            for (ItemStack item : overflow.values()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), item);
            }
        }

        return grave;
    }

    private Inventory createInventory(UUID graveId, String ownerName) {
        GraveHolder holder = new GraveHolder(graveId);
        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text(ownerName + "'s Grave", NamedTextColor.DARK_RED)
        );
        holder.setInventory(inventory);
        return inventory;
    }

    private Location findGraveLocation(Location deathLocation) {
        World world = deathLocation.getWorld();
        if (world == null || !plugin.isHeroesWorld(world)) {
            return null;
        }

        int baseX = deathLocation.getBlockX();
        int baseY = deathLocation.getBlockY();
        int baseZ = deathLocation.getBlockZ();

        int[][] horizontalOffsets = {
                {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        int[] verticalOffsets = {0, 1, 2, -1};

        for (int yOffset : verticalOffsets) {
            int y = baseY + yOffset;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            for (int[] offset : horizontalOffsets) {
                Block block = world.getBlockAt(baseX + offset[0], y, baseZ + offset[1]);
                if (block.getType().isAir()) {
                    return block.getLocation();
                }
            }
        }
        return null;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Grave grave : new ArrayList<>(graves.values())) {
            if (now >= grave.expiresAt()) {
                destroy(grave, true);
                continue;
            }

            Block block = grave.location().getBlock();
            if (block.getType() != Material.PLAYER_HEAD) {
                // If another plugin removed the head, never leave invisible stored loot.
                destroy(grave, true);
                continue;
            }

            updateDisplay(grave, now);
        }
    }

    private void spawnDisplay(Grave grave) {
        removeDisplay(grave);
        Location displayLocation = grave.location().clone().add(0.5D, 1.35D, 0.5D);
        TextDisplay display = grave.location().getWorld().spawn(
                displayLocation,
                TextDisplay.class,
                entity -> {
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setShadowed(true);
                    entity.setSeeThrough(false);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setLineWidth(260);
                }
        );
        grave.setDisplay(display);
        updateDisplay(grave, System.currentTimeMillis());
    }

    private void updateDisplay(Grave grave, long now) {
        TextDisplay display = grave.display();
        if (display == null || !display.isValid()) {
            spawnDisplay(grave);
            return;
        }

        long remainingMillis = Math.max(0L, grave.expiresAt() - now);
        long totalSeconds = (remainingMillis + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        String timer = String.format("%02d:%02d", minutes, seconds);

        Component text = Component.text(grave.ownerName() + "'s Grave", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Despawns in " + timer, NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text(grave.deathCause(), NamedTextColor.GRAY));

        display.text(text);
    }

    private void removeDisplay(Grave grave) {
        TextDisplay display = grave.display();
        if (display != null && display.isValid()) {
            display.remove();
        }
        grave.setDisplay(null);
    }

    private void destroy(Grave grave, boolean dropItems) {
        if (graves.remove(grave.id()) == null) {
            return;
        }

        gravesByBlock.remove(BlockKey.of(grave.location().getBlock()));

        for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(grave.inventory().getViewers())) {
            viewer.closeInventory();
        }

        removeDisplay(grave);

        Block block = grave.location().getBlock();
        if (block.getType() == Material.PLAYER_HEAD) {
            block.setType(Material.AIR, false);
        }

        if (dropItems) {
            Location dropLocation = grave.location().clone().add(0.5D, 0.35D, 0.5D);
            dropLocation.getChunk().load();
            for (ItemStack item : grave.inventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    grave.location().getWorld().dropItemNaturally(dropLocation, item.clone());
                }
            }
        }

        grave.inventory().clear();
        save();
    }

    private Grave graveAt(Block block) {
        UUID id = gravesByBlock.get(BlockKey.of(block));
        return id == null ? null : graves.get(id);
    }

    private boolean isEmpty(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private String describeDeath(Player player) {
        EntityDamageEvent damage = player.getLastDamageCause();
        if (damage instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Entity entity) {
                    damager = entity;
                }
            }

            if (damager instanceof Player killer) {
                return "Killed by " + killer.getName();
            }

            return "Killed by " + prettyName(damager.getType().name());
        }

        if (damage == null) {
            return "Died";
        }

        return switch (damage.getCause().name()) {
            case "FALL" -> "Hit the ground too hard";
            case "FIRE", "FIRE_TICK" -> "Burned to death";
            case "LAVA" -> "Tried to swim in lava";
            case "DROWNING" -> "Drowned";
            case "BLOCK_EXPLOSION", "ENTITY_EXPLOSION" -> "Blew up";
            case "VOID" -> "Fell into the void";
            case "SUFFOCATION" -> "Suffocated in a wall";
            case "STARVATION" -> "Starved to death";
            case "LIGHTNING" -> "Was struck by lightning";
            case "POISON" -> "Was poisoned";
            case "WITHER" -> "Withered away";
            case "FREEZE" -> "Froze to death";
            case "CONTACT" -> "Was pricked to death";
            case "FLY_INTO_WALL" -> "Experienced kinetic energy";
            case "HOT_FLOOR" -> "Discovered the floor was lava";
            case "CRAMMING" -> "Was squished too much";
            case "MAGIC" -> "Was killed by magic";
            case "SONIC_BOOM" -> "Was obliterated by a sonic boom";
            case "WORLD_BORDER" -> "Left the world border";
            default -> "Died from " + prettyName(damage.getCause().name());
        };
    }

    private String prettyName(String raw) {
        String[] words = raw.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }
        return result.toString();
    }

    private void load() {
        graves.clear();
        gravesByBlock.clear();

        if (!dataFile.isFile()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = yaml.getConfigurationSection("graves");
        if (section == null) {
            return;
        }

        for (String idText : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(idText);
                String path = "graves." + idText + ".";
                UUID owner = UUID.fromString(yaml.getString(path + "owner", ""));
                String ownerName = yaml.getString(path + "owner-name", "Unknown");
                World world = Bukkit.getWorld(yaml.getString(path + "world", ""));
                if (world == null || !plugin.isHeroesWorld(world)) {
                    continue;
                }

                Location location = new Location(
                        world,
                        yaml.getInt(path + "x"),
                        yaml.getInt(path + "y"),
                        yaml.getInt(path + "z")
                );
                long expiresAt = yaml.getLong(path + "expires-at");
                String deathCause = yaml.getString(path + "death-cause", "Died");

                Inventory inventory = createInventory(id, ownerName);
                List<?> stored = yaml.getList(path + "items", List.of());
                List<ItemStack> items = new ArrayList<>();
                for (Object value : stored) {
                    if (value instanceof ItemStack item && !item.getType().isAir()) {
                        items.add(item.clone());
                    }
                }
                inventory.addItem(items.toArray(ItemStack[]::new));

                Grave grave = new Grave(
                        id,
                        owner,
                        ownerName,
                        location,
                        deathCause,
                        expiresAt,
                        inventory,
                        null
                );
                graves.put(id, grave);
                gravesByBlock.put(BlockKey.of(location.getBlock()), id);
            } catch (Exception exception) {
                plugin.getLogger().warning(
                        "Ignored broken grave entry " + idText + ": " + exception.getMessage()
                );
            }
        }
    }

    private void restoreLoadedGraves() {
        long now = System.currentTimeMillis();
        for (Grave grave : new ArrayList<>(graves.values())) {
            if (grave.expiresAt() <= now) {
                destroy(grave, true);
                continue;
            }

            Location location = grave.location();
            location.getChunk().load();
            Block block = location.getBlock();
            if (block.getType() != Material.PLAYER_HEAD) {
                Location replacement = findGraveLocation(location);
                if (replacement == null) {
                    destroy(grave, true);
                    continue;
                }
                gravesByBlock.remove(BlockKey.of(block));
                grave.setLocation(replacement.getBlock().getLocation());
                block = grave.location().getBlock();
                block.setType(Material.PLAYER_HEAD, false);
                gravesByBlock.put(BlockKey.of(block), grave.id());
            }

            if (block.getState() instanceof Skull skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(grave.owner()));
                skull.getPersistentDataContainer().set(
                        graveKey,
                        org.bukkit.persistence.PersistentDataType.STRING,
                        grave.id().toString()
                );
                skull.update(true, false);
            }
            spawnDisplay(grave);
        }
    }

    private void save() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (Grave grave : graves.values()) {
            String path = "graves." + grave.id() + ".";
            yaml.set(path + "owner", grave.owner().toString());
            yaml.set(path + "owner-name", grave.ownerName());
            yaml.set(path + "world", grave.location().getWorld().getName());
            yaml.set(path + "x", grave.location().getBlockX());
            yaml.set(path + "y", grave.location().getBlockY());
            yaml.set(path + "z", grave.location().getBlockZ());
            yaml.set(path + "expires-at", grave.expiresAt());
            yaml.set(path + "death-cause", grave.deathCause());

            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : grave.inventory().getContents()) {
                if (item != null && !item.getType().isAir()) {
                    items.add(item.clone());
                }
            }
            yaml.set(path + "items", items);
        }

        try {
            yaml.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save graves.yml: " + exception.getMessage());
        }
    }

    private static final class GraveHolder implements InventoryHolder {
        private final UUID graveId;
        private Inventory inventory;

        private GraveHolder(UUID graveId) {
            this.graveId = graveId;
        }

        UUID graveId() {
            return graveId;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Grave {
        private final UUID id;
        private final UUID owner;
        private final String ownerName;
        private Location location;
        private final String deathCause;
        private final long expiresAt;
        private final Inventory inventory;
        private TextDisplay display;

        private Grave(
                UUID id,
                UUID owner,
                String ownerName,
                Location location,
                String deathCause,
                long expiresAt,
                Inventory inventory,
                TextDisplay display
        ) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.location = location;
            this.deathCause = deathCause;
            this.expiresAt = expiresAt;
            this.inventory = inventory;
            this.display = display;
        }

        UUID id() { return id; }
        UUID owner() { return owner; }
        String ownerName() { return ownerName; }
        Location location() { return location; }
        String deathCause() { return deathCause; }
        long expiresAt() { return expiresAt; }
        Inventory inventory() { return inventory; }
        TextDisplay display() { return display; }
        void setDisplay(TextDisplay display) { this.display = display; }
        void setLocation(Location location) { this.location = location; }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }
    }
}
