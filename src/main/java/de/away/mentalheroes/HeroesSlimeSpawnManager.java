package de.away.mentalheroes;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives MentalHEROS surface slimes a biome-independent night spawn.
 * Other modes/worlds are intentionally untouched.
 */
final class HeroesSlimeSpawnManager implements Listener {

    private static final long NIGHT_START = 13000L;
    private static final long NIGHT_END = 23000L;
    private static final int MAX_NEARBY_SLIMES = 6;

    private final MentalHeroesPlugin plugin;
    private BukkitTask task;

    HeroesSlimeSpawnManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                100L,
                100L
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSlimeSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Slime)
                || !plugin.isHeroesWorld(event.getLocation().getWorld())
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (!isNight(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.isHeroesWorld(player)) {
                continue;
            }

            World world = player.getWorld();
            if (!isNight(world)) {
                continue;
            }

            // Natural-feeling rate instead of flooding every player with slimes.
            if (ThreadLocalRandom.current().nextDouble() > 0.35D) {
                continue;
            }

            long nearby = world.getNearbyEntities(
                    player.getLocation(),
                    48.0D,
                    32.0D,
                    48.0D,
                    entity -> entity instanceof Slime
            ).size();
            if (nearby >= MAX_NEARBY_SLIMES) {
                continue;
            }

            Location spawn = findSurfaceSpawn(player);
            if (spawn == null) {
                continue;
            }

            Entity entity = world.spawnEntity(
                    spawn,
                    org.bukkit.entity.EntityType.SLIME,
                    CreatureSpawnEvent.SpawnReason.NATURAL
            );
            if (entity instanceof Slime slime) {
                int roll = ThreadLocalRandom.current().nextInt(10);
                slime.setSize(roll == 0 ? 4 : roll < 4 ? 2 : 1);
            }
        }
    }

    private Location findSurfaceSpawn(Player player) {
        World world = player.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble(Math.PI * 2.0D);
            int distance = random.nextInt(18, 33);
            int x = player.getLocation().getBlockX()
                    + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getLocation().getBlockZ()
                    + (int) Math.round(Math.sin(angle) * distance);

            int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (groundY <= world.getMinHeight() || groundY + 2 >= world.getMaxHeight()) {
                continue;
            }

            Block ground = world.getBlockAt(x, groundY, z);
            Block feet = world.getBlockAt(x, groundY + 1, z);
            Block head = world.getBlockAt(x, groundY + 2, z);

            if (!ground.getType().isSolid()
                    || ground.getType() == Material.BEDROCK
                    || !feet.isPassable()
                    || !head.isPassable()
                    || feet.isLiquid()
                    || head.isLiquid()) {
                continue;
            }

            return new Location(world, x + 0.5D, groundY + 1.0D, z + 0.5D);
        }
        return null;
    }

    private boolean isNight(World world) {
        if (world == null) {
            return false;
        }
        long time = world.getTime();
        return time >= NIGHT_START && time <= NIGHT_END;
    }
}
