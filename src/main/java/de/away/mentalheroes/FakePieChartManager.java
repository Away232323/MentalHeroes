package de.away.mentalheroes;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MentalHEROS-only anti Pie-Ray noise.
 *
 * Sends client-side fake CHEST/SPAWNER block entities inside fully enclosed
 * solid blocks around the player. The real world is never modified. The fake
 * positions are distributed around the player so profiler-based block-entity
 * direction checks receive constant decoy signals instead of pointing at real
 * bases/containers.
 */
final class FakePieChartManager implements Listener {

    private static final int[] RADII = {12, 22};
    private static final int DIRECTIONS = 12;
    private static final int SEARCH_DEPTH = 28;
    private static final int REFRESH_DISTANCE_SQUARED = 36;

    private final MentalHeroesPlugin plugin;
    private final Map<UUID, List<Location>> fakeLocations = new HashMap<>();
    private final Map<UUID, Location> anchors = new HashMap<>();
    private BukkitTask task;

    FakePieChartManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                20L
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player, true);
        }
        fakeLocations.clear();
        anchors.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline() && plugin.isHeroesWorld(player)) {
                refresh(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        clear(player, false);
        if (plugin.isHeroesWorld(player)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && plugin.isHeroesWorld(player)) {
                    refresh(player);
                }
            }, 10L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer(), false);
    }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.isHeroesWorld(player)) {
                clear(player, false);
                continue;
            }

            Location anchor = anchors.get(player.getUniqueId());
            if (anchor == null
                    || anchor.getWorld() != player.getWorld()
                    || anchor.distanceSquared(player.getLocation()) >= REFRESH_DISTANCE_SQUARED) {
                refresh(player);
            }
        }
    }

    private void refresh(Player player) {
        clear(player, true);

        World world = player.getWorld();
        Location center = player.getLocation();
        List<Location> sent = new ArrayList<>();
        int index = 0;

        for (int radius : RADII) {
            for (int direction = 0; direction < DIRECTIONS; direction++) {
                double angle = Math.PI * 2.0D * direction / DIRECTIONS;
                int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
                Location location = findHiddenSolid(world, x, center.getBlockY() - 4, z);
                if (location == null) {
                    continue;
                }

                Material fakeType = (index++ & 1) == 0
                        ? Material.CHEST
                        : Material.SPAWNER;
                sendFakeTile(player, location, fakeType);
                sent.add(location);
            }
        }

        fakeLocations.put(player.getUniqueId(), sent);
        anchors.put(player.getUniqueId(), center.clone());
    }

    private Location findHiddenSolid(World world, int x, int startY, int z) {
        int maximumY = Math.min(startY, world.getMaxHeight() - 2);
        int minimumY = Math.max(world.getMinHeight() + 2, maximumY - SEARCH_DEPTH);

        for (int y = maximumY; y >= minimumY; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!safeHost(block)) {
                continue;
            }
            if (safeHost(world.getBlockAt(x + 1, y, z))
                    && safeHost(world.getBlockAt(x - 1, y, z))
                    && safeHost(world.getBlockAt(x, y + 1, z))
                    && safeHost(world.getBlockAt(x, y - 1, z))
                    && safeHost(world.getBlockAt(x, y, z + 1))
                    && safeHost(world.getBlockAt(x, y, z - 1))) {
                return block.getLocation();
            }
        }
        return null;
    }

    private boolean safeHost(Block block) {
        if (block == null
                || !block.getType().isSolid()
                || block.isLiquid()
                || block.getState() instanceof TileState) {
            return false;
        }
        return block.getType() != Material.BEDROCK
                && block.getType() != Material.BARRIER
                && block.getType() != Material.END_PORTAL_FRAME;
    }

    private void sendFakeTile(Player player, Location location, Material material) {
        BlockData data = material.createBlockData();
        player.sendBlockChange(location, data);

        BlockState state = data.createBlockState();
        if (state instanceof TileState tileState) {
            player.sendBlockUpdate(location, tileState);
        }
    }

    private void clear(Player player, boolean restoreVisibleWorld) {
        UUID uuid = player.getUniqueId();
        List<Location> locations = fakeLocations.remove(uuid);
        anchors.remove(uuid);
        if (locations == null || locations.isEmpty() || !restoreVisibleWorld) {
            return;
        }

        for (Location location : locations) {
            if (location.getWorld() != player.getWorld()) {
                continue;
            }
            Block real = location.getBlock();
            player.sendBlockChange(location, real.getBlockData());
            BlockState state = real.getState();
            if (state instanceof TileState tileState) {
                player.sendBlockUpdate(location, tileState);
            }
        }
    }
}
