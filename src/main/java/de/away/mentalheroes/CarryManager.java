package de.away.mentalheroes;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class CarryManager implements Listener {

    private static final double MAX_MOB_WIDTH = 1.55D;
    private static final double MAX_MOB_HEIGHT = 1.85D;
    private static final double PLACE_RANGE = 5.0D;
    private static final float MOB_DISPLAY_SCALE = 0.55F;

    private final MentalHeroesPlugin plugin;
    private final NamespacedKey carriedMobKey;
    private final NamespacedKey originalAiKey;
    private final NamespacedKey originalGravityKey;
    private final NamespacedKey originalInvulnerableKey;
    private final NamespacedKey originalSilentKey;
    private final NamespacedKey originalCollidableKey;
    private final NamespacedKey originalScaleKey;
    private final File recoveryFile;
    private final YamlConfiguration recoveryData;
    private final Map<UUID, CarrySession> sessions = new HashMap<>();

    private BukkitTask tickTask;

    public CarryManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.carriedMobKey = new NamespacedKey(
                plugin,
                "carried_mob"
        );
        this.originalAiKey = new NamespacedKey(
                plugin,
                "carry_original_ai"
        );
        this.originalGravityKey = new NamespacedKey(
                plugin,
                "carry_original_gravity"
        );
        this.originalInvulnerableKey = new NamespacedKey(
                plugin,
                "carry_original_invulnerable"
        );
        this.originalSilentKey = new NamespacedKey(
                plugin,
                "carry_original_silent"
        );
        this.originalCollidableKey = new NamespacedKey(
                plugin,
                "carry_original_collidable"
        );
        this.originalScaleKey = new NamespacedKey(
                plugin,
                "carry_original_scale"
        );
        this.recoveryFile = new File(
                plugin.getDataFolder(),
                "carried-spawners.yml"
        );
        this.recoveryData = YamlConfiguration.loadConfiguration(
                recoveryFile
        );
    }

    public void start() {
        recoverInterruptedSpawners();
        recoverOrphanedMobs();

        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                1L
        );
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            CarrySession session = sessions.get(playerId);

            if (session == null) {
                continue;
            }

            Location location = player != null
                    ? player.getLocation()
                    : session.fallbackLocation();
            releaseAt(playerId, location);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        CarrySession current = sessions.get(player.getUniqueId());

        if (current != null) {
            event.setCancelled(true);
            tryPlace(player, null, null);
            return;
        }

        if (isCarriedMob(event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }

        if (!player.isSneaking()
                || !areHandsEmpty(player)
                || player.getGameMode() == GameMode.SPECTATOR
                || !canCarryMob(event.getRightClicked())) {
            return;
        }

        event.setCancelled(true);
        pickUpMob(player, (Animals) event.getRightClicked());
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            tryPlace(
                    player,
                    event.getClickedBlock(),
                    event.getBlockFace()
            );
            return;
        }

        Block clicked = event.getClickedBlock();

        if (clicked == null
                || clicked.getType() != Material.SPAWNER
                || !player.isSneaking()
                || !areHandsEmpty(player)
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        event.setCancelled(true);
        pickUpSpawner(player, clicked);
    }

    private boolean canCarryMob(Entity entity) {
        return entity instanceof Animals
                && entity.isValid()
                && entity.getVehicle() == null
                && entity.getPassengers().isEmpty()
                && entity.getWidth() <= MAX_MOB_WIDTH
                && entity.getHeight() <= MAX_MOB_HEIGHT;
    }

    private void pickUpMob(Player player, Animals mob) {
        EntitySnapshot snapshot = mob.createSnapshot();

        if (snapshot == null) {
            return;
        }

        Location origin = mob.getLocation().clone();
        Entity carriedDisplay;

        try {
            mob.remove();
            carriedDisplay = snapshot.createEntity(
                    carryLocation(player)
            );
        } catch (RuntimeException exception) {
            if (!mob.isValid()) {
                snapshot.createEntity(origin);
            }

            plugin.getLogger().warning(
                    "Could not pick up mob: " + exception.getMessage()
            );
            return;
        }

        freezeCarriedMob(carriedDisplay, player.getUniqueId());

        MobCarrySession session = new MobCarrySession(
                player.getUniqueId(),
                player.getWalkSpeed(),
                origin,
                snapshot,
                carriedDisplay
        );

        sessions.put(player.getUniqueId(), session);
        applyCarrySpeed(player, 0.68F);
        player.getWorld().playSound(
                player.getLocation(),
                Sound.ENTITY_ITEM_PICKUP,
                0.8F,
                0.75F
        );
    }

    private void pickUpSpawner(Player player, Block block) {
        BlockState snapshot = block.getState().copy();
        ItemStack storedSpawner = createSpawnerItem(snapshot);

        saveSpawnerRecovery(
                player.getUniqueId(),
                block.getLocation(),
                storedSpawner
        );

        block.setType(Material.AIR, false);

        BlockDisplay display = spawnSpawnerDisplay(
                player,
                snapshot
        );

        SpawnerCarrySession session = new SpawnerCarrySession(
                player.getUniqueId(),
                player.getWalkSpeed(),
                block.getLocation().clone(),
                storedSpawner,
                display
        );

        sessions.put(player.getUniqueId(), session);
        applyCarrySpeed(player, 0.58F);
        player.getWorld().playSound(
                player.getLocation(),
                Sound.BLOCK_CHAIN_BREAK,
                0.85F,
                0.8F
        );
    }

    private ItemStack createSpawnerItem(BlockState snapshot) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        meta.setBlockState(snapshot);
        item.setItemMeta(meta);
        return item;
    }

    private BlockDisplay spawnSpawnerDisplay(
            Player player,
            BlockState snapshot
    ) {
        BlockDisplay display = player.getWorld().spawn(
                player.getLocation(),
                BlockDisplay.class
        );

        display.setBlock(snapshot.getBlockData());
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setTeleportDuration(0);
        display.setInterpolationDuration(1);
        display.setShadowRadius(0.0F);
        display.setViewRange(1.25F);
        display.setTransformation(
                new Transformation(
                        new Vector3f(-0.34F, -0.88F, 0.58F),
                        new Quaternionf(),
                        new Vector3f(0.68F, 0.68F, 0.68F),
                        new Quaternionf()
                )
        );
        display.setRotation(player.getLocation().getYaw(), 0.0F);
        player.addPassenger(display);
        return display;
    }

    private void tryPlace(
            Player player,
            Block clicked,
            BlockFace face
    ) {
        CarrySession session = sessions.get(player.getUniqueId());

        if (session instanceof SpawnerCarrySession spawnerSession) {
            Block target = resolveSpawnerTarget(player, clicked, face);

            if (target == null) {
                cannotPlace(player);
                return;
            }

            if (!restoreSpawnerState(
                    spawnerSession.storedSpawner,
                    target
            )) {
                cannotPlace(player);
                return;
            }

            endSpawnerSession(player, spawnerSession);
            player.getWorld().playSound(
                    target.getLocation(),
                    Sound.BLOCK_CHAIN_PLACE,
                    0.9F,
                    1.05F
            );
            return;
        }

        if (session instanceof MobCarrySession mobSession) {
            Location target = resolveMobTarget(player, clicked, face);

            if (target == null) {
                cannotPlace(player);
                return;
            }

            try {
                mobSession.snapshot.createEntity(target);
            } catch (RuntimeException exception) {
                cannotPlace(player);
                plugin.getLogger().warning(
                        "Could not place carried mob: "
                                + exception.getMessage()
                );
                return;
            }

            mobSession.display.remove();
            sessions.remove(player.getUniqueId());
            restorePlayerSpeed(player, mobSession);
            player.getWorld().playSound(
                    target,
                    Sound.ENTITY_ITEM_PICKUP,
                    0.8F,
                    1.25F
            );
        }
    }

    private Block resolveSpawnerTarget(
            Player player,
            Block clicked,
            BlockFace face
    ) {
        Block target = adjacentTarget(player, clicked, face);

        if (target == null
                || !target.getType().isAir()
                || target.getY() < target.getWorld().getMinHeight()
                || target.getY() >= target.getWorld().getMaxHeight()) {
            return null;
        }

        return target;
    }

    private Location resolveMobTarget(
            Player player,
            Block clicked,
            BlockFace face
    ) {
        Block target = adjacentTarget(player, clicked, face);

        if (target == null) {
            Location ahead = player.getLocation().clone().add(
                    player.getLocation()
                            .getDirection()
                            .setY(0.0D)
                            .normalize()
                            .multiply(2.0D)
            );
            target = ahead.getBlock();
        }

        for (int offset = 2; offset >= -3; offset--) {
            Block feet = target.getRelative(0, offset, 0);
            Block head = feet.getRelative(BlockFace.UP);
            Block ground = feet.getRelative(BlockFace.DOWN);

            if (feet.isPassable()
                    && head.isPassable()
                    && ground.getType().isSolid()) {
                return feet.getLocation().add(0.5D, 0.05D, 0.5D);
            }
        }

        return null;
    }

    private Block adjacentTarget(
            Player player,
            Block clicked,
            BlockFace face
    ) {
        if (clicked != null && face != null) {
            return clicked.getRelative(face);
        }

        RayTraceResult trace = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                PLACE_RANGE,
                FluidCollisionMode.NEVER,
                true
        );

        if (trace == null
                || trace.getHitBlock() == null
                || trace.getHitBlockFace() == null) {
            return null;
        }

        return trace.getHitBlock().getRelative(
                trace.getHitBlockFace()
        );
    }

    private boolean restoreSpawnerState(
            ItemStack storedSpawner,
            Block target
    ) {
        if (!(storedSpawner.getItemMeta()
                instanceof BlockStateMeta meta)) {
            return false;
        }

        try {
            target.setType(Material.SPAWNER, false);
            BlockState copied = meta.getBlockState().copy(
                    target.getLocation()
            );
            return copied.update(true, false);
        } catch (RuntimeException exception) {
            target.setType(Material.AIR, false);
            plugin.getLogger().warning(
                    "Could not restore spawner: "
                            + exception.getMessage()
            );
            return false;
        }
    }

    private void cannotPlace(Player player) {
        player.getWorld().playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                0.65F,
                0.65F
        );
    }

    private void tick() {
        for (Map.Entry<UUID, CarrySession> entry
                : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            CarrySession session = entry.getValue();

            if (player == null || !player.isOnline()) {
                releaseAt(entry.getKey(), session.fallbackLocation());
                continue;
            }

            if (player.isDead()) {
                releaseAt(entry.getKey(), player.getLocation());
                continue;
            }

            if (session instanceof MobCarrySession mobSession) {
                if (!mobSession.display.isValid()) {
                    sessions.remove(entry.getKey());
                    restorePlayerSpeed(player, mobSession);
                    mobSession.snapshot.createEntity(player.getLocation());
                    continue;
                }

                mobSession.display.teleport(carryLocation(player));
                mobSession.display.setVelocity(new Vector());
                mobSession.display.setFireTicks(0);
            } else if (session
                    instanceof SpawnerCarrySession spawnerSession) {
                updateSpawnerDisplay(player, spawnerSession);
            }
        }
    }

    private Location carryLocation(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location location = eye.add(
                direction.multiply(1.22D)
        ).add(0.0D, -0.82D, 0.0D);

        location.setYaw(player.getLocation().getYaw());
        location.setPitch(0.0F);
        return location;
    }

    private void updateSpawnerDisplay(
            Player player,
            SpawnerCarrySession session
    ) {
        BlockDisplay display = session.display;

        if (!display.isValid()
                || !display.getWorld().equals(player.getWorld())) {
            if (display.isValid()) {
                display.remove();
            }

            BlockStateMeta meta = (BlockStateMeta)
                    session.storedSpawner.getItemMeta();
            session.display = spawnSpawnerDisplay(
                    player,
                    meta.getBlockState()
            );
            return;
        }

        if (!player.getPassengers().contains(display)) {
            player.addPassenger(display);
        }

        display.setRotation(player.getLocation().getYaw(), 0.0F);
    }

    private void freezeCarriedMob(Entity entity, UUID carrier) {
        PersistentDataContainer data = entity.getPersistentDataContainer();

        data.set(
                carriedMobKey,
                PersistentDataType.STRING,
                carrier.toString()
        );
        setBoolean(data, originalGravityKey, entity.hasGravity());
        setBoolean(
                data,
                originalInvulnerableKey,
                entity.isInvulnerable()
        );
        setBoolean(data, originalSilentKey, entity.isSilent());

        if (entity instanceof LivingEntity living) {
            setBoolean(data, originalAiKey, living.hasAI());
            setBoolean(
                    data,
                    originalCollidableKey,
                    living.isCollidable()
            );

            AttributeInstance scale = living.getAttribute(
                    Attribute.SCALE
            );

            if (scale != null) {
                data.set(
                        originalScaleKey,
                        PersistentDataType.DOUBLE,
                        scale.getBaseValue()
                );
                scale.setBaseValue(
                        Math.max(
                                0.2D,
                                scale.getBaseValue()
                                        * MOB_DISPLAY_SCALE
                        )
                );
            }

            living.setAI(false);
            living.setCollidable(false);
        }

        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setPersistent(true);
        entity.setVelocity(new Vector());
    }

    private void recoverOrphanedMobs() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isCarriedMob(entity)) {
                    unfreezeCarriedMob(entity);
                }
            }
        }
    }

    private void unfreezeCarriedMob(Entity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();

        entity.setGravity(getBoolean(
                data,
                originalGravityKey,
                true
        ));
        entity.setInvulnerable(getBoolean(
                data,
                originalInvulnerableKey,
                false
        ));
        entity.setSilent(getBoolean(
                data,
                originalSilentKey,
                false
        ));

        if (entity instanceof LivingEntity living) {
            living.setAI(getBoolean(data, originalAiKey, true));
            living.setCollidable(getBoolean(
                    data,
                    originalCollidableKey,
                    true
            ));

            Double originalScale = data.get(
                    originalScaleKey,
                    PersistentDataType.DOUBLE
            );
            AttributeInstance scale = living.getAttribute(
                    Attribute.SCALE
            );

            if (originalScale != null && scale != null) {
                scale.setBaseValue(originalScale);
            }
        }

        data.remove(carriedMobKey);
        data.remove(originalAiKey);
        data.remove(originalGravityKey);
        data.remove(originalInvulnerableKey);
        data.remove(originalSilentKey);
        data.remove(originalCollidableKey);
        data.remove(originalScaleKey);
    }

    private boolean isCarriedMob(Entity entity) {
        return entity.getPersistentDataContainer().has(
                carriedMobKey,
                PersistentDataType.STRING
        );
    }

    private void setBoolean(
            PersistentDataContainer data,
            NamespacedKey key,
            boolean value
    ) {
        data.set(
                key,
                PersistentDataType.BYTE,
                (byte) (value ? 1 : 0)
        );
    }

    private boolean getBoolean(
            PersistentDataContainer data,
            NamespacedKey key,
            boolean fallback
    ) {
        Byte value = data.get(key, PersistentDataType.BYTE);
        return value == null ? fallback : value == 1;
    }

    private void applyCarrySpeed(Player player, float multiplier) {
        player.setWalkSpeed(
                Math.max(0.05F, player.getWalkSpeed() * multiplier)
        );
    }

    private void restorePlayerSpeed(
            Player player,
            CarrySession session
    ) {
        player.setWalkSpeed(session.originalWalkSpeed);
    }

    private boolean areHandsEmpty(Player player) {
        return player.getInventory().getItemInMainHand()
                .getType().isAir()
                && player.getInventory().getItemInOffHand()
                .getType().isAir();
    }

    private void endSpawnerSession(
            Player player,
            SpawnerCarrySession session
    ) {
        session.display.remove();
        sessions.remove(player.getUniqueId());
        restorePlayerSpeed(player, session);
        clearSpawnerRecovery(player.getUniqueId());
    }

    private void releaseAt(UUID playerId, Location location) {
        CarrySession session = sessions.get(playerId);

        if (session == null) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);

        if (session instanceof MobCarrySession mobSession) {
            Location safe = findSafeMobLocation(location);

            try {
                mobSession.snapshot.createEntity(safe);
                mobSession.display.remove();
            } catch (RuntimeException exception) {
                unfreezeCarriedMob(mobSession.display);
                mobSession.display.teleport(safe);
            }
        } else if (session
                instanceof SpawnerCarrySession spawnerSession) {
            Block target = findSafeSpawnerBlock(
                    location.getBlock(),
                    spawnerSession.fallbackLocation().getBlock()
            );

            if (target != null && restoreSpawnerState(
                    spawnerSession.storedSpawner,
                    target
            )) {
                clearSpawnerRecovery(playerId);
            }

            spawnerSession.display.remove();
        }

        sessions.remove(playerId);

        if (player != null && player.isOnline()) {
            restorePlayerSpeed(player, session);
        }
    }

    private Location findSafeMobLocation(Location start) {
        Block base = start.getBlock();

        for (int radius = 0; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = 2; y >= -3; y--) {
                        Block feet = base.getRelative(x, y, z);

                        if (feet.isPassable()
                                && feet.getRelative(BlockFace.UP)
                                .isPassable()
                                && feet.getRelative(BlockFace.DOWN)
                                .getType().isSolid()) {
                            return feet.getLocation().add(
                                    0.5D,
                                    0.05D,
                                    0.5D
                            );
                        }
                    }
                }
            }
        }

        return start.clone();
    }

    private Block findSafeSpawnerBlock(
            Block preferred,
            Block fallback
    ) {
        if (preferred.getType().isAir()) {
            return preferred;
        }

        if (fallback.getType().isAir()) {
            return fallback;
        }

        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        Block candidate = preferred.getRelative(x, y, z);

                        if (candidate.getType().isAir()) {
                            return candidate;
                        }
                    }
                }
            }
        }

        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isCarriedMob(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = responsiblePlayer(event.getDamager());

        if (attacker != null
                && sessions.containsKey(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();

            if (shooter instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        releaseAt(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getLocation()
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        releaseAt(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getLocation()
        );
    }

    private void saveSpawnerRecovery(
            UUID playerId,
            Location origin,
            ItemStack storedSpawner
    ) {
        String path = "spawners." + playerId;

        recoveryData.set(path + ".world", origin.getWorld().getName());
        recoveryData.set(path + ".x", origin.getBlockX());
        recoveryData.set(path + ".y", origin.getBlockY());
        recoveryData.set(path + ".z", origin.getBlockZ());
        recoveryData.set(path + ".item", storedSpawner);
        saveRecoveryData();
    }

    private void clearSpawnerRecovery(UUID playerId) {
        recoveryData.set("spawners." + playerId, null);
        saveRecoveryData();
    }

    private void recoverInterruptedSpawners() {
        ConfigurationSection section = recoveryData
                .getConfigurationSection("spawners");

        if (section == null) {
            return;
        }

        boolean changed = false;

        for (String playerId : section.getKeys(false)) {
            String path = "spawners." + playerId;
            World world = Bukkit.getWorld(
                    recoveryData.getString(path + ".world", "")
            );
            ItemStack item = recoveryData.getItemStack(path + ".item");

            if (world == null || item == null) {
                continue;
            }

            Block origin = world.getBlockAt(
                    recoveryData.getInt(path + ".x"),
                    recoveryData.getInt(path + ".y"),
                    recoveryData.getInt(path + ".z")
            );

            if (origin.getType() == Material.SPAWNER) {
                recoveryData.set(path, null);
                changed = true;
                continue;
            }

            Block target = findSafeSpawnerBlock(origin, origin);

            if (target != null && restoreSpawnerState(item, target)) {
                recoveryData.set(path, null);
                changed = true;
            }
        }

        if (changed) {
            saveRecoveryData();
        }
    }

    private void saveRecoveryData() {
        try {
            recoveryData.save(recoveryFile);
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "The carried-spawners.yml file could not be saved!"
            );
            exception.printStackTrace();
        }
    }

    private abstract static class CarrySession {

        private final UUID playerId;
        private final float originalWalkSpeed;
        private final Location origin;

        private CarrySession(
                UUID playerId,
                float originalWalkSpeed,
                Location origin
        ) {
            this.playerId = playerId;
            this.originalWalkSpeed = originalWalkSpeed;
            this.origin = origin;
        }

        final Location fallbackLocation() {
            return origin.clone();
        }
    }

    private static final class MobCarrySession extends CarrySession {

        private final EntitySnapshot snapshot;
        private final Entity display;

        private MobCarrySession(
                UUID playerId,
                float originalWalkSpeed,
                Location origin,
                EntitySnapshot snapshot,
                Entity display
        ) {
            super(playerId, originalWalkSpeed, origin);
            this.snapshot = snapshot;
            this.display = display;
        }
    }

    private static final class SpawnerCarrySession
            extends CarrySession {

        private final ItemStack storedSpawner;
        private BlockDisplay display;

        private SpawnerCarrySession(
                UUID playerId,
                float originalWalkSpeed,
                Location origin,
                ItemStack storedSpawner,
                BlockDisplay display
        ) {
            super(playerId, originalWalkSpeed, origin);
            this.storedSpawner = storedSpawner;
            this.display = display;
        }
    }
}
