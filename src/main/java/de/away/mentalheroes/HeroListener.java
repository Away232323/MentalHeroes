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
import java.util.Map;
import java.util.UUID;

public final class HeroListener implements Listener {

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;
    private final HudManager hudManager;
    private final HeartLossAnimationManager heartLossAnimationManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, UUID> crystalAttackers = new HashMap<>();

    public HeroListener(
            MentalHeroesPlugin plugin,
            HeartManager heartManager,
            CombatManager combatManager,
            HudManager hudManager,
            HeartLossAnimationManager heartLossAnimationManager
    ) {
        this.plugin = plugin;
        this.heartManager = heartManager;
        this.combatManager = combatManager;
        this.hudManager = hudManager;
        this.heartLossAnimationManager = heartLossAnimationManager;
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
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        // Remember who destroyed an end crystal for its explosion damage.
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

        Player killer = player.getKiller();

        if (killer == null
                && player.getLastDamageCause()
                instanceof EntityDamageByEntityEvent lastDamage) {
            killer = findResponsiblePlayer(lastDamage.getDamager());
        }

        if (killer != null
                && combatManager.areOpponents(
                        killer.getUniqueId(),
                        uuid
                )) {
            combatManager.clear(killer.getUniqueId());
            sendConfigured(
                    killer,
                    "messages.combat-ended",
                    Map.of()
            );
        }

        combatManager.clear(uuid);

        int currentHearts = heartManager.getHearts(uuid);

        if (currentHearts <= 0) {
            return;
        }

        int remainingHearts = heartManager.removeHeart(uuid);
        heartLossAnimationManager.queueAnimation(uuid);

        sendConfigured(
                player,
                "messages.heart-lost",
                Map.of(
                        "hearts",
                        String.valueOf(remainingHearts)
                )
        );
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    hudManager.update(player);

                    boolean animationStarted =
                            heartLossAnimationManager
                                    .playPendingAnimation(
                                            player,
                                            () -> {
                                                hudManager.update(player);

                                                if (heartManager.getHearts(
                                                        player.getUniqueId()
                                                ) <= 0) {
                                                    banPlayer(player);
                                                }
                                            }
                                    );

                    if (!animationStarted
                            && heartManager.getHearts(
                                    player.getUniqueId()
                            ) <= 0) {
                        banPlayer(player);
                    }
                },
                10L
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        hudManager.hideCombatBar(player);

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
                "<red>You have lost all of your Hero Hearts!</red>"
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
                "<red>Missing message: " + path + "</red>"
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

}
