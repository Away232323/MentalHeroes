package de.away.mentalheroes;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeartLossAnimationManager implements Listener {

    private static final Key ANIMATION_FONT =
            Key.key("mentalheroes", "heart_loss_animation");

    private static final String[] FRAMES = {
            "\uE100", "\uE101", "\uE102", "\uE103",
            "\uE104", "\uE105", "\uE106", "\uE107",
            "\uE108", "\uE109", "\uE10A", "\uE10B"
    };

    private static final long FRAME_TICKS = 2L;

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;
    private final Set<UUID> animatingPlayers = new HashSet<>();
    private final Set<UUID> animatedDeaths = new HashSet<>();
    private final Map<UUID, UUID> pendingKillers = new HashMap<>();

    public HeartLossAnimationManager(
            MentalHeroesPlugin plugin,
            HeartManager heartManager,
            CombatManager combatManager
    ) {
        this.plugin = plugin;
        this.heartManager = heartManager;
        this.combatManager = combatManager;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (animatingPlayers.contains(uuid)) {
            event.setCancelled(true);
            return;
        }

        if (event.getFinalDamage() < player.getHealth()) {
            return;
        }

        if (hasTotemReady(player)) {
            return;
        }

        Player attacker = findResponsiblePlayer(event);

        if (!combatManager.isInCombat(uuid)
                && attacker != null
                && !attacker.getUniqueId().equals(uuid)) {
            combatManager.tag(attacker, player);
        }

        if (!combatManager.isInCombat(uuid)
                || heartManager.getHearts(uuid) <= 0) {
            return;
        }

        event.setCancelled(true);
        player.setFallDistance(0.0F);
        animatingPlayers.add(uuid);

        if (attacker != null) {
            pendingKillers.put(uuid, attacker.getUniqueId());
        }

        playAnimation(player);
    }

    private void playAnimation(Player player) {
        UUID uuid = player.getUniqueId();
        Title.Times times = Title.Times.times(
                Duration.ZERO,
                Duration.ofMillis(140),
                Duration.ZERO
        );

        for (int index = 0; index < FRAMES.length; index++) {
            String frame = FRAMES[index];
            long delay = index * FRAME_TICKS;

            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> showFrame(player, uuid, frame, times),
                    delay
            );
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> finishAnimation(player, uuid),
                FRAMES.length * FRAME_TICKS + 1L
        );
    }

    private void showFrame(
            Player player,
            UUID uuid,
            String frame,
            Title.Times times
    ) {
        if (!player.isOnline() || !animatingPlayers.contains(uuid)) {
            return;
        }

        player.showTitle(
                Title.title(
                        Component.text(frame).font(ANIMATION_FONT),
                        Component.empty(),
                        times
                )
        );

        if (frame.equals("\uE104")) {
            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                    0.9F,
                    0.75F
            );
        }
    }

    private void finishAnimation(Player player, UUID uuid) {
        if (!animatingPlayers.remove(uuid)) {
            return;
        }

        player.clearTitle();

        if (!player.isOnline() || player.isDead()) {
            pendingKillers.remove(uuid);
            return;
        }

        animatedDeaths.add(uuid);
        player.setHealth(0.0D);
    }

    public boolean consumeAnimatedDeath(UUID uuid) {
        return animatedDeaths.remove(uuid);
    }

    public UUID consumePendingKiller(UUID uuid) {
        return pendingKillers.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        animatingPlayers.remove(uuid);
        animatedDeaths.remove(uuid);
        pendingKillers.remove(uuid);
        event.getPlayer().clearTitle();
    }

    public void stop() {
        for (UUID uuid : animatingPlayers) {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null) {
                player.clearTitle();
            }
        }

        animatingPlayers.clear();
        animatedDeaths.clear();
        pendingKillers.clear();
    }

    private boolean hasTotemReady(Player player) {
        return player.getInventory().getItemInMainHand().getType()
                == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType()
                == Material.TOTEM_OF_UNDYING;
    }

    private Player findResponsiblePlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }

        Entity damager = byEntity.getDamager();

        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();

            if (shooter instanceof Player player) {
                return player;
            }
        }

        if (damager instanceof TNTPrimed tnt
                && tnt.getSource() instanceof Player player) {
            return player;
        }

        if (damager instanceof Tameable tameable
                && tameable.getOwner() instanceof Player player) {
            return player;
        }

        return null;
    }
}
