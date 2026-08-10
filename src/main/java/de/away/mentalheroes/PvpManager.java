package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PvpManager
        implements Listener, CommandExecutor, TabCompleter {

    private final MentalHeroesPlugin plugin;
    private boolean enabled;

    public PvpManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean(
                "pvp.enabled",
                false
        );
    }

    public void start() {
        applyWorldPvpState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        event.getWorld().setPVP(enabled);
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = true
    )
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (enabled || !(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = findAttacker(event);

        if (attacker == null
                || attacker.getUniqueId().equals(
                        victim.getUniqueId()
                )) {
            return;
        }

        event.setCancelled(true);
    }

    private Player findAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }

        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }

        return null;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("mentalheroes.command.pvp")) {
            sender.sendMessage(Component.text(
                    "You do not have permission to do that.",
                    NamedTextColor.RED
            ));
            return true;
        }

        if (args.length == 0
                || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(Component.text(
                    "Usage: /" + label + " <on|off|status>",
                    NamedTextColor.RED
            ));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "enable" -> setEnabled(sender, true);
            case "off", "disable" -> setEnabled(sender, false);
            default -> sender.sendMessage(Component.text(
                    "Usage: /" + label + " <on|off|status>",
                    NamedTextColor.RED
            ));
        }

        return true;
    }

    private void setEnabled(CommandSender sender, boolean newState) {
        enabled = newState;
        plugin.getConfig().set("pvp.enabled", newState);
        plugin.saveConfig();
        applyWorldPvpState();

        sender.sendMessage(Component.text(
                newState
                        ? "PvP is now enabled."
                        : "PvP is now disabled.",
                newState
                        ? NamedTextColor.GREEN
                        : NamedTextColor.RED
        ));
    }

    private void applyWorldPvpState() {
        Bukkit.getWorlds().forEach(world -> world.setPVP(enabled));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text(
                enabled
                        ? "PvP is currently enabled."
                        : "PvP is currently disabled.",
                enabled
                        ? NamedTextColor.GREEN
                        : NamedTextColor.RED
        ));
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1
                || !sender.hasPermission(
                        "mentalheroes.command.pvp"
                )) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        for (String option : List.of("on", "off", "status")) {
            if (option.startsWith(prefix)) {
                completions.add(option);
            }
        }

        return completions;
    }
}
