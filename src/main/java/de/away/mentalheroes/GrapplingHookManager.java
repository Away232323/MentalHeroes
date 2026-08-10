package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GrapplingHookManager implements Listener {

    private static final double MAX_REACH = 15.0D;
    private static final double CHAIN_LINK_SPACING = 0.72D;
    private static final float CHAIN_LINK_SCALE = 0.62F;

    private final MentalHeroesPlugin plugin;
    private final GrapplingHookItems items;
    private final org.bukkit.NamespacedKey ownerKey;
    private final Map<UUID, HookSession> sessions = new HashMap<>();

    private BukkitTask tickTask;

    public GrapplingHookManager(
            MentalHeroesPlugin plugin,
            GrapplingHookItems items
    ) {
        this.plugin = plugin;
        this.items = items;
        this.ownerKey = new org.bukkit.NamespacedKey(
                plugin,
                "grappling_owner"
        );
    }

    public void start() {
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

        for (HookSession session : sessions.values()) {
            removeEntities(session);
        }

        sessions.clear();
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = false
    )
    public void onUse(PlayerInteractEvent event) {
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

        GrapplingHookTier tier = items.getTier(item);

        if (tier == null || !items.isHook(item)) {
            return;
        }

        event.setCancelled(true);

        if (sessions.containsKey(player.getUniqueId())) {
            detach(player.getUniqueId());
            player.setFallDistance(0.0F);
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.ENTITY_FISHING_BOBBER_RETRIEVE,
                    0.8F,
                    1.15F
            );
            return;
        }

        shoot(player, hand, tier);
    }

    private void shoot(
            Player player,
            EquipmentSlot hand,
            GrapplingHookTier tier
    ) {
        detach(player.getUniqueId());

        Location start = player.getEyeLocation();
        Vector direction = start.getDirection().normalize();
        start.add(direction.clone().multiply(0.65D));

        double speed = plugin.getConfig().getDouble(
                "grappling-hook.projectile-speed",
                2.25D
        );

        Snowball projectile = player.getWorld().spawn(
                start,
                Snowball.class
        );

        projectile.setShooter(player);
        projectile.setVelocity(direction.multiply(speed));
        projectile.setItem(items.createHead(tier));
        projectile.setGravity(true);
        projectile.setPersistent(false);
        projectile.getPersistentDataContainer().set(
                ownerKey,
                PersistentDataType.STRING,
                player.getUniqueId().toString()
        );

        HookSession session = new HookSession(
                tier,
                projectile,
                hand,
                player.getEyeLocation()
        );

        sessions.put(player.getUniqueId(), session);
        player.damageItemStack(hand, 1);
        player.setCooldown(Material.FISHING_ROD, 5);
        player.getWorld().playSound(
                player.getLocation(),
                Sound.ENTITY_FISHING_BOBBER_THROW,
                0.9F,
                0.8F
        );
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String ownerText = projectile
                .getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING);

        if (ownerText == null) {
            return;
        }

        UUID owner;

        try {
            owner = UUID.fromString(ownerText);
        } catch (IllegalArgumentException exception) {
            projectile.remove();
            return;
        }

        HookSession session = sessions.get(owner);

        if (session == null
                || session.projectile == null
                || !session.projectile.getUniqueId().equals(
                        projectile.getUniqueId()
                )) {
            projectile.remove();
            return;
        }

        if (event.getHitBlock() == null) {
            detach(owner);
            projectile.remove();
            return;
        }

        session.anchor = projectile.getLocation().clone();
        session.projectile = null;
        session.attached = true;

        Entity hitEntity = event.getHitEntity();

        if (hitEntity != null
                && !hitEntity.getUniqueId().equals(owner)) {
            session.targetEntity = hitEntity.getUniqueId();
            session.anchor = null;
        }

        projectile.remove();
        spawnAnchorDisplay(session);

        Player player = Bukkit.getPlayer(owner);

        if (player != null) {
            player.getWorld().playSound(
                    player.getLocation(),
                    Sound.BLOCK_CHAIN_PLACE,
                    0.8F,
                    1.25F
            );
        }
    }

    private void spawnAnchorDisplay(HookSession session) {
        Location endpoint = getEndpoint(session);

        if (endpoint == null) {
            return;
        }

        ItemDisplay display = endpoint.getWorld().spawn(
                endpoint,
                ItemDisplay.class
        );

        display.setItemStack(items.createHead(session.tier));
        display.setItemDisplayTransform(
                ItemDisplay.ItemDisplayTransform.FIXED
        );
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setInvulnerable(true);
        session.anchorDisplay = display;
    }

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onPull(PlayerSwapHandItemsEvent event) {
        HookSession session = sessions.get(
                event.getPlayer().getUniqueId()
        );

        if (session == null) {
            return;
        }

        event.setCancelled(true);

        if (!session.attached) {
            event.getPlayer().sendActionBar(
                    Component.text(
                            "Der Haken fliegt noch …",
                            NamedTextColor.GRAY
                    )
            );
            return;
        }

        session.pullingTicks = 24;
        event.getPlayer().setFallDistance(0.0F);
        event.getPlayer().getWorld().playSound(
                event.getPlayer().getLocation(),
                Sound.BLOCK_CHAIN_HIT,
                0.65F,
                1.4F
        );
    }

    private void tick() {
        Iterator<Map.Entry<UUID, HookSession>> iterator =
                sessions.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, HookSession> entry = iterator.next();
            HookSession session = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null
                    || !player.isOnline()
                    || player.isDead()) {
                removeEntities(session);
                iterator.remove();
                continue;
            }

            if (!isHoldingSessionHook(player, session)) {
                removeEntities(session);
                iterator.remove();
                continue;
            }

            session.ageTicks++;
            Location endpoint = getEndpoint(session);

            if (endpoint == null
                    || endpoint.getWorld() != player.getWorld()) {
                removeEntities(session);
                iterator.remove();
                continue;
            }

            double maxDistanceSquared = MAX_REACH * MAX_REACH;

            if (player.getLocation().distanceSquared(endpoint)
                    > maxDistanceSquared
                    || (!session.attached
                    && session.launchOrigin.distanceSquared(endpoint)
                    > maxDistanceSquared)
                    || session.ageTicks
                    > plugin.getConfig().getInt(
                            "grappling-hook.max-lifetime-ticks",
                            1200
                    )) {
                removeEntities(session);
                iterator.remove();
                continue;
            }

            if (session.anchorDisplay != null
                    && session.targetEntity != null) {
                session.anchorDisplay.teleport(endpoint);
            }

            updateChain(player, endpoint, session);

            if (!session.attached
                    || session.pullingTicks <= 0) {
                continue;
            }

            pullPlayer(player, endpoint);
            session.pullingTicks--;
        }
    }

    private boolean isHoldingSessionHook(
            Player player,
            HookSession session
    ) {
        ItemStack heldItem = session.hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        return items.isHook(heldItem)
                && items.getTier(heldItem) == session.tier;
    }

    private void pullPlayer(Player player, Location endpoint) {
        Location body = player.getLocation().add(0.0D, 0.65D, 0.0D);
        Vector difference = endpoint.toVector()
                .subtract(body.toVector());
        double distance = difference.length();

        player.setFallDistance(0.0F);

        if (distance < 1.8D) {
            return;
        }

        double configuredMaximum = plugin.getConfig().getDouble(
                "grappling-hook.maximum-pull-speed",
                1.85D
        );
        double speed = Math.min(
                configuredMaximum,
                0.55D + (distance * 0.04D)
        );

        Vector velocity = difference.normalize().multiply(speed);
        velocity.setY(Math.min(1.65D, velocity.getY() + 0.18D));
        player.setVelocity(velocity);
    }

    private void updateChain(
            Player player,
            Location endpoint,
            HookSession session
    ) {
        Location start = getChainStart(player, session.hand);
        Vector line = endpoint.toVector()
                .subtract(start.toVector());
        double length = line.length();

        if (length <= 0.05D) {
            clearChainDisplays(session);
            return;
        }

        Vector direction = line.clone().normalize();
        int linkCount = Math.min(
                28,
                Math.max(
                        1,
                        (int) Math.ceil(
                                length / CHAIN_LINK_SPACING
                        )
                )
        );

        while (session.chainDisplays.size() < linkCount) {
            ItemDisplay display = player.getWorld().spawn(
                    start,
                    ItemDisplay.class
            );

            display.setItemStack(
                    items.createChainLink(session.tier)
            );
            display.setItemDisplayTransform(
                    ItemDisplay.ItemDisplayTransform.FIXED
            );
            display.setBillboard(Display.Billboard.FIXED);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            display.setViewRange(0.75F);
            display.setShadowRadius(0.0F);
            session.chainDisplays.add(display);
        }

        while (session.chainDisplays.size() > linkCount) {
            int lastIndex = session.chainDisplays.size() - 1;
            ItemDisplay display = session.chainDisplays.remove(
                    lastIndex
            );
            display.remove();
        }

        Vector3f chainDirection = new Vector3f(
                (float) direction.getX(),
                (float) direction.getY(),
                (float) direction.getZ()
        );

        for (int index = 0; index < linkCount; index++) {
            double progress = (index + 0.5D) / linkCount;
            Location position = start.clone().add(
                    line.clone().multiply(progress)
            );

            Quaternionf rotation = new Quaternionf().rotationTo(
                    new Vector3f(0.0F, 1.0F, 0.0F),
                    chainDirection
            );

            if ((index & 1) == 1) {
                rotation.rotateLocalY((float) (Math.PI / 2.0D));
            }

            ItemDisplay display = session.chainDisplays.get(index);
            display.setTransformation(
                    new Transformation(
                            new Vector3f(),
                            rotation,
                            new Vector3f(
                                    CHAIN_LINK_SCALE,
                                    CHAIN_LINK_SCALE,
                                    CHAIN_LINK_SCALE
                            ),
                            new Quaternionf()
                    )
            );
            display.teleport(position);
        }
    }

    private Location getChainStart(
            Player player,
            EquipmentSlot hand
    ) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(
                new Vector(0.0D, 1.0D, 0.0D)
        );

        if (right.lengthSquared() > 0.0001D) {
            right.normalize().multiply(
                    hand == EquipmentSlot.OFF_HAND
                            ? -0.32D
                            : 0.32D
            );
        }

        return eye.add(forward.multiply(0.78D))
                .add(right)
                .add(0.0D, -0.28D, 0.0D);
    }

    private void clearChainDisplays(HookSession session) {
        for (ItemDisplay display : session.chainDisplays) {
            if (display.isValid()) {
                display.remove();
            }
        }

        session.chainDisplays.clear();
    }

    private Location getEndpoint(HookSession session) {
        if (!session.attached) {
            if (session.projectile == null
                    || !session.projectile.isValid()) {
                return null;
            }

            return session.projectile.getLocation();
        }

        if (session.targetEntity != null) {
            Entity target = Bukkit.getEntity(session.targetEntity);

            if (target == null || !target.isValid()) {
                return null;
            }

            return target.getLocation().add(
                    0.0D,
                    Math.max(0.35D, target.getHeight() * 0.55D),
                    0.0D
            );
        }

        return session.anchor == null
                ? null
                : session.anchor.clone();
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }

        HookSession session = sessions.get(
                player.getUniqueId()
        );

        if (session == null || !session.attached) {
            return;
        }

        event.setCancelled(true);
        player.setFallDistance(0.0F);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        detach(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        detach(event.getPlayer().getUniqueId());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld()
                != event.getFrom().getWorld()
                || event.getTo().distanceSquared(event.getFrom())
                > 16.0D) {
            detach(event.getPlayer().getUniqueId());
        }
    }

    private void detach(UUID playerUuid) {
        HookSession session = sessions.remove(playerUuid);

        if (session != null) {
            removeEntities(session);
        }
    }

    private void removeEntities(HookSession session) {
        if (session.projectile != null
                && session.projectile.isValid()) {
            session.projectile.remove();
        }

        if (session.anchorDisplay != null
                && session.anchorDisplay.isValid()) {
            session.anchorDisplay.remove();
        }

        clearChainDisplays(session);
    }

    private static final class HookSession {

        private final GrapplingHookTier tier;
        private final EquipmentSlot hand;
        private final Location launchOrigin;
        private final List<ItemDisplay> chainDisplays =
                new ArrayList<>();
        private Projectile projectile;
        private Location anchor;
        private UUID targetEntity;
        private ItemDisplay anchorDisplay;
        private boolean attached;
        private int pullingTicks;
        private int ageTicks;

        private HookSession(
                GrapplingHookTier tier,
                Projectile projectile,
                EquipmentSlot hand,
                Location launchOrigin
        ) {
            this.tier = tier;
            this.projectile = projectile;
            this.hand = hand;
            this.launchOrigin = launchOrigin;
        }
    }
}
