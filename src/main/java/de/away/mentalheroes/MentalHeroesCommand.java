package de.away.mentalheroes;

import io.papermc.paper.ban.BanListType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MentalHeroesCommand
        implements CommandExecutor, TabCompleter {

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final HudManager hudManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MentalHeroesCommand(
            MentalHeroesPlugin plugin,
            HeartManager heartManager,
            HudManager hudManager
    ) {
        this.plugin = plugin;
        this.heartManager = heartManager;
        this.hudManager = hudManager;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "hearts" -> handleHearts(sender, label, args);
            case "sethearts" -> handleSetHearts(sender, label, args);
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void handleHearts(
            CommandSender sender,
            String label,
            String[] args
    ) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sendRaw(
                        sender,
                        "<red>Benutzung: /"
                                + label
                                + " hearts <Spieler></red>"
                );
                return;
            }

            if (!sender.hasPermission(
                    "mentalheroes.command.hearts"
            )) {
                sendConfigured(sender, "messages.no-permission");
                return;
            }

            int hearts = heartManager.getHearts(
                    player.getUniqueId()
            );

            sendConfigured(
                    sender,
                    "messages.own-hearts",
                    Map.of("hearts", String.valueOf(hearts))
            );

            return;
        }

        if (args.length != 2) {
            sendRaw(
                    sender,
                    "<red>Benutzung: /"
                            + label
                            + " hearts [Spieler]</red>"
            );
            return;
        }

        if (!sender.hasPermission(
                "mentalheroes.command.hearts.others"
        )) {
            sendConfigured(sender, "messages.no-permission");
            return;
        }

        OfflinePlayer target = findPlayer(args[1]);

        if (target == null) {
            sendConfigured(sender, "messages.player-not-found");
            return;
        }

        int hearts = heartManager.getHearts(
                target.getUniqueId()
        );

        String targetName = target.getName() == null
                ? args[1]
                : target.getName();

        sendConfigured(
                sender,
                "messages.other-hearts",
                Map.of(
                        "player", targetName,
                        "hearts", String.valueOf(hearts)
                )
        );
    }

    private void handleSetHearts(
            CommandSender sender,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission(
                "mentalheroes.command.sethearts"
        )) {
            sendConfigured(sender, "messages.no-permission");
            return;
        }

        if (args.length != 3) {
            sendRaw(
                    sender,
                    "<red>Benutzung: /"
                            + label
                            + " sethearts <Spieler> <0-3></red>"
            );
            return;
        }

        OfflinePlayer target = findPlayer(args[1]);

        if (target == null) {
            sendConfigured(sender, "messages.player-not-found");
            return;
        }

        int newHearts;

        try {
            newHearts = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            sendConfigured(sender, "messages.invalid-number");
            return;
        }

        int maximum = heartManager.getMaximumHearts();

        if (newHearts < 0 || newHearts > maximum) {
            sendConfigured(sender, "messages.invalid-number");
            return;
        }

        heartManager.setHearts(
                target.getUniqueId(),
                newHearts
        );

        String targetName = target.getName() == null
                ? args[1]
                : target.getName();

        if (newHearts == 0) {
            banPlayer(target);
        } else {
            unbanPlayer(target);
        }

        Player onlineTarget = target.getPlayer();

        if (onlineTarget != null && onlineTarget.isOnline()) {
            hudManager.update(onlineTarget);

            if (newHearts > 0
                    && onlineTarget != sender) {
                sendConfigured(
                        onlineTarget,
                        "messages.hearts-received",
                        Map.of(
                                "hearts",
                                String.valueOf(newHearts)
                        )
                );
            }
        }

        sendConfigured(
                sender,
                "messages.hearts-set",
                Map.of(
                        "player", targetName,
                        "hearts", String.valueOf(newHearts)
                )
        );
    }

    private OfflinePlayer findPlayer(String name) {
        Player onlinePlayer = Bukkit.getPlayerExact(name);

        if (onlinePlayer != null) {
            return onlinePlayer;
        }

        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private void banPlayer(OfflinePlayer target) {
        String reasonText = plugin.getConfig().getString(
                "ban.reason",
                "<red>Du hast alle deine Heldenherzen verloren!</red>"
        );

        Component reasonComponent =
                miniMessage.deserialize(reasonText);

        String plainReason =
                PlainTextComponentSerializer.plainText()
                        .serialize(reasonComponent);

        target.ban(
                plainReason,
                (Date) null,
                "MentalHeroes"
        );

        Player onlinePlayer = target.getPlayer();

        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            onlinePlayer.kick(reasonComponent);
        }
    }

    private void unbanPlayer(OfflinePlayer target) {
        if (!target.isBanned()) {
            return;
        }

        Bukkit.getBanList(BanListType.PROFILE).pardon(
                target.getPlayerProfile()
        );
    }

    private void sendHelp(
            CommandSender sender,
            String label
    ) {
        sendRaw(
                sender,
                "<dark_aqua><bold>MentalHeroes Commands</bold></dark_aqua>"
        );

        sendRaw(
                sender,
                "<gray>/"
                        + label
                        + " hearts [Spieler]</gray>"
        );

        if (sender.hasPermission(
                "mentalheroes.command.sethearts"
        )) {
            sendRaw(
                    sender,
                    "<gray>/"
                            + label
                            + " sethearts <Spieler> <0-3></gray>"
            );
        }
    }

    private void sendConfigured(
            CommandSender sender,
            String path
    ) {
        sendConfigured(sender, path, Map.of());
    }

    private void sendConfigured(
            CommandSender sender,
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

        sender.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }

    private void sendRaw(
            CommandSender sender,
            String message
    ) {
        sender.sendMessage(
                miniMessage.deserialize(message)
        );
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>();

            if (sender.hasPermission(
                    "mentalheroes.command.hearts"
            )) {
                subCommands.add("hearts");
            }

            if (sender.hasPermission(
                    "mentalheroes.command.sethearts"
            )) {
                subCommands.add("sethearts");
            }

            return filter(subCommands, args[0]);
        }

        if (args.length == 2
                && (
                args[0].equalsIgnoreCase("hearts")
                        || args[0].equalsIgnoreCase("sethearts")
        )) {
            List<String> playerNames = Bukkit.getOnlinePlayers()
                    .stream()
                    .map(Player::getName)
                    .toList();

            return filter(playerNames, args[1]);
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("sethearts")) {
            return filter(
                    List.of("0", "1", "2", "3"),
                    args[2]
            );
        }

        return List.of();
    }

    private List<String> filter(
            List<String> choices,
            String input
    ) {
        String lowerInput = input.toLowerCase(Locale.ROOT);

        return choices.stream()
                .filter(choice ->
                        choice.toLowerCase(Locale.ROOT)
                                .startsWith(lowerInput)
                )
                .toList();
    }
}
