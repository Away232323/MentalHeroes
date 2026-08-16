package de.away.mentalheroes;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Repairs generic lobby/protection leftovers without changing the actual
 * MentalHeroes PvP switch. PvP remains owned by PvpManager.
 */
final class DamageCompatibilityManager implements Listener {

    private final MentalHeroesPlugin plugin;

    DamageCompatibilityManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        enableWorldDamage();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isHeroesWorld(player)) {
                preparePlayer(player);
            }
        }

        // Re-register after startup so generic lobby/protection listeners that
        // were registered later cannot keep swallowing normal PvE damage.
        Bukkit.getScheduler().runTask(plugin, this::claimFinalListenerPosition);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                this::claimFinalListenerPosition,
                40L
        );
    }

    private void claimFinalListenerPosition() {
        if (!plugin.isEnabled()) {
            return;
        }
        HandlerList.unregisterAll(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        enableWorldDamage();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isHeroesWorld(player)) {
                preparePlayer(player);
            }
        }
    }

    private void enableWorldDamage() {
        Bukkit.getWorlds().stream()
                .filter(plugin::isHeroesWorld)
                .forEach(world -> world.setPVP(true));
    }

    private void preparePlayer(Player player) {
        player.setInvulnerable(false);
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setNoDamageTicks(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !plugin.isHeroesWorld(player)) {
            return;
        }

        // PvP is intentionally handled by PvpManager, including /pvp off.
        if (event instanceof EntityDamageByEntityEvent byEntity
                && isPlayerAttack(byEntity)) {
            return;
        }

        // GrapplingHookManager intentionally cancels fall damage while a hook
        // is attached. Do not override a cancelled FALL event here.
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.isCancelled()) {
            return;
        }

        // Everything else should be normal survival damage: mobs, arrows from
        // mobs, fire, lava, explosions, drowning, suffocation, poison, etc.
        event.setCancelled(false);
    }

    private boolean isPlayerAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            return true;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isHeroesWorld(event.getPlayer())) {
                enableWorldDamage();
                preparePlayer(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isHeroesWorld(event.getPlayer())) {
                enableWorldDamage();
                preparePlayer(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isHeroesWorld(event.getPlayer())) {
                enableWorldDamage();
                preparePlayer(event.getPlayer());
            }
        });
    }
}
