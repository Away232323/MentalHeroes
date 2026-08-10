package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;

public final class DimensionManager implements Listener {

    private final MentalHeroesPlugin plugin;

    public DimensionManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                this::relocatePlayersAndUnloadDimensions,
                1L
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onPortalCreate(PortalCreateEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onPlayerPortal(PlayerPortalEvent event) {
        event.setCancelled(true);
        sendDisabledMessage(event.getPlayer());
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onEntityPortal(EntityPortalEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (isBlocked(event.getTo())) {
            event.setCancelled(true);
            sendDisabledMessage(event.getPlayer());
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (isBlocked(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!isBlocked(event.getRespawnLocation())) {
            return;
        }

        Location fallback = getFallbackLocation();

        if (fallback != null) {
            event.setRespawnLocation(fallback);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!isBlocked(event.getPlayer().getLocation())) {
            return;
        }

        Bukkit.getScheduler().runTask(
                plugin,
                () -> moveToOverworld(event.getPlayer())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!isBlocked(event.getWorld())) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> unloadDimension(event.getWorld()),
                1L
        );
    }

    private void relocatePlayersAndUnloadDimensions() {
        for (World world : Bukkit.getWorlds().toArray(World[]::new)) {
            if (isBlocked(world)) {
                unloadDimension(world);
            }
        }
    }

    private void unloadDimension(World world) {
        if (!isBlocked(world)) {
            return;
        }

        for (Player player : world.getPlayers()) {
            moveToOverworld(player);
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (world.getPlayers().isEmpty()
                            && Bukkit.getWorld(world.getUID()) != null) {
                        Bukkit.unloadWorld(world, true);
                    }
                },
                1L
        );
    }

    private void moveToOverworld(Player player) {
        Location fallback = getFallbackLocation();

        if (fallback != null) {
            player.teleport(fallback);
        }
    }

    private Location getFallbackLocation() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world.getSpawnLocation();
            }
        }

        return null;
    }

    private boolean isBlocked(Location location) {
        return location != null && isBlocked(location.getWorld());
    }

    private boolean isBlocked(World world) {
        return world != null
                && world.getEnvironment() != World.Environment.NORMAL;
    }

    private void sendDisabledMessage(Player player) {
        player.sendActionBar(Component.text(
                "The Nether and End are disabled.",
                NamedTextColor.RED
        ));
    }
}
