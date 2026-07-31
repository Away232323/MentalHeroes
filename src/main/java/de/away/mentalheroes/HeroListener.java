package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HeroListener implements Listener {

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;
    private final HudManager hudManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, UUID> crystalAttackers = new HashMap<>();
    private final Set<UUID> directPlayerDeaths = new HashSet<>();

    public HeroListener(
            MentalHeroesPlugin plugin,
            HeartManager heartManager,
            CombatManager combatManager,
            HudManager hudManager
    ) {
        this.plugin = plugin;
        this.heartManager = heartManager;
        this.combatManager = combatManager;
        this.hudManager = hudManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int hearts = heartManager.getHearts(player.getUniqueId());

        if (hearts <= 0) {
            banPlayer(player);
            return;
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> hudManager.update(player),
                10L
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        /*
         * Wenn ein Spieler einen Endkristall zerstört,
         * merken wir uns diesen Spieler kurz als Verursacher.
         */
        if (event.getEntity() instanceof EnderCrystal crystal) {
            Player crystalAttacker = findResponsiblePlayer(
                    event.getDamager()
            );

            if (crystalAttacker != null) {
                crystalAttackers.put(
                        crystal.getUniqueId(),
                        crystalAttacker.getUniqueId()
                );

                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> crystalAttackers.remove(
                                crystal.getUniqueId()
                        ),
                        2L
                );
            }

            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        if (event.getFinalDamage() <= 0) {
            return;
        }

        Player attacker = findResponsiblePlayer(
                event.getDamager()
        );

        if (attacker == null) {
            return;
        }

        if (attacker.getUniqueId().equals(
                victim.getUniqueId()
        )) {
            return;
        }

        combatManager.tag(attacker, victim);

        /*
         * Wir merken uns, wenn dieser Treffer den Spieler
         * direkt töten wird. Das ist für das goldene Herz.
         */
        if (event.getFinalDamage() >= victim.getHealth()) {
            directPlayerDeaths.add(victim.getUniqueId());

            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> directPlayerDeaths.remove(
                            victim.getUniqueId()
                    ),
                    1L
            );
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!combatManager.isInCombat(uuid)) {
            return;
        }

        combatManager.clear(uuid);

        int currentHearts = heartManager.getHearts(uuid);

        if (currentHearts <= 0) {
            return;
        }

        boolean directPlayerKill =
                directPlayerDeaths.remove(uuid);

        /*
         * Das letzte goldene Herz geht vorläufig nur bei
         * einem direkten finalen Spieler-Treffer verloren.
         */
        if (currentHearts == 1 && !directPlayerKill) {
            sendRaw(
                    player,
                    "<gold>Dein goldenes Herz wurde geschützt, "
                            + "weil der letzte Treffer nicht direkt "
                            + "von einem Spieler kam.</gold>"
            );

            return;
        }

        int remainingHearts = heartManager.removeHeart(uuid);

        sendConfigured(
                player,
                "messages.heart-lost",
                Map.of(
                        "hearts",
                        String.valueOf(remainingHearts)
                )
        );

        if (remainingHearts <= 0) {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> banPlayer(player)
            );
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> hudManager.update(player),
                5L
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!combatManager.isInCombat(uuid)) {
            return;
        }

        combatManager.clear(uuid);

        boolean loseHeart = plugin.getConfig().getBoolean(
                "combat.logout-loses-heart",
                true
        );

        if (!loseHeart) {
            return;
        }

        int currentHearts = heartManager.getHearts(uuid);

        if (currentHearts <= 0) {
            return;
        }

        int remainingHearts = heartManager.removeHeart(uuid);

        if (remainingHearts <= 0) {
            banPlayer(player);
        }
    }

    private Player findResponsiblePlayer(Entity damager) {
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

        if (damager instanceof EnderCrystal crystal) {
            UUID attackerUuid = crystalAttackers.get(
                    crystal.getUniqueId()
            );

            if (attackerUuid != null) {
                return Bukkit.getPlayer(attackerUuid);
            }
        }

        return null;
    }

    private void banPlayer(OfflinePlayer player) {
        String rawReason = plugin.getConfig().getString(
                "ban.reason",
                "<red>Du hast alle deine Heldenherzen verloren!</red>"
        );

        Component reasonComponent =
                miniMessage.deserialize(rawReason);

        String plainReason =
                PlainTextComponentSerializer.plainText()
                        .serialize(reasonComponent);

        player.ban(
                plainReason,
                (Date) null,
                "MentalHeroes"
        );

        Player onlinePlayer = player.getPlayer();

        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            onlinePlayer.kick(reasonComponent);
        }
    }

    private void sendConfigured(
            Player player,
            String path,
            Map<String, String> replacements
    ) {
        String prefix = plugin.getConfig().getString(
                "messages.prefix",
                ""
        );

        String message = plugin.getConfig().getString(
                path,
                "<red>Fehlende Nachricht: " + path + "</red>"
        );

        for (Map.Entry<String, String> replacement
                : replacements.entrySet()) {
            message = message.replace(
                    "<" + replacement.getKey() + ">",
                    replacement.getValue()
            );
        }

        player.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }

    private void sendRaw(
            Player player,
            String message
    ) {
        String prefix = plugin.getConfig().getString(
                "messages.prefix",
                ""
        );

        player.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }
}
