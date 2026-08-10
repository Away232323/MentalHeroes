package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlaytimeManager
        implements Listener, CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION =
            "mentalheroes.command.playtime.admin";
    private static final String PLAYER_PERMISSION =
            "mentalheroes.command.playtime";

    private final MentalHeroesPlugin plugin;
    private final File dataFile;
    private final YamlConfiguration data;
    private final Map<UUID, Integer> usedSeconds = new HashMap<>();
    private final Set<UUID> hiddenBossBars = new HashSet<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final ZoneId resetZone;
    private final int dailySeconds;

    private LocalDate storedDate;
    private BukkitTask timerTask;
    private boolean enabled;
    private boolean dirty;
    private int secondsUntilSave = 15;

    public PlaytimeManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean(
                "playtime.enabled",
                true
        );
        this.dailySeconds = Math.max(
                1,
                plugin.getConfig().getInt(
                        "playtime.daily-seconds",
                        3600
                )
        );
        this.resetZone = loadResetZone();

        if (!plugin.getDataFolder().exists()
                && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning(
                    "Could not create the plugin data folder."
            );
        }

        this.dataFile = new File(
                plugin.getDataFolder(),
                "playtime.yml"
        );
        this.data = YamlConfiguration.loadConfiguration(dataFile);

        loadData();
        ensureDailyReset();
    }

    private ZoneId loadResetZone() {
        String configuredZone = plugin.getConfig().getString(
                "playtime.reset-timezone",
                "Europe/Vienna"
        );

        try {
            return ZoneId.of(configuredZone);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning(
                    "Invalid playtime reset timezone '"
                            + configuredZone
                            + "'. Using Europe/Vienna."
            );
            return ZoneId.of("Europe/Vienna");
        }
    }

    private void loadData() {
        String dateText = data.getString("date", "");

        try {
            storedDate = LocalDate.parse(dateText);
        } catch (DateTimeException exception) {
            storedDate = LocalDate.now(resetZone);
            dirty = true;
        }

        ConfigurationSection usedSection =
                data.getConfigurationSection("used-seconds");

        if (usedSection != null) {
            for (String uuidText : usedSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidText);
                    int seconds = Math.max(
                            0,
                            usedSection.getInt(uuidText)
                    );
                    usedSeconds.put(uuid, seconds);
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning(
                            "Ignored invalid UUID in playtime.yml: "
                                    + uuidText
                    );
                }
            }
        }

        for (String uuidText
                : data.getStringList("hidden-boss-bars")) {
            try {
                hiddenBossBars.add(UUID.fromString(uuidText));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning(
                        "Ignored invalid hidden boss bar UUID: "
                                + uuidText
                );
            }
        }
    }

    public void start() {
        timerTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                20L
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            handleOnlinePlayer(player);
        }
    }

    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        for (BossBar bossBar : bossBars.values()) {
            bossBar.removeAll();
        }

        bossBars.clear();
        saveData();
    }

    private void tick() {
        ensureDailyReset();

        if (enabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                int used = Math.min(
                        dailySeconds,
                        usedSeconds.getOrDefault(uuid, 0) + 1
                );

                usedSeconds.put(uuid, used);
                dirty = true;

                if (used >= dailySeconds) {
                    hideBossBar(player);
                    player.kick(limitReachedMessage());
                    continue;
                }

                updateBossBar(player);
            }
        }

        secondsUntilSave--;

        if (secondsUntilSave <= 0) {
            saveData();
            secondsUntilSave = 15;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        ensureDailyReset();

        if (enabled && getRemainingSeconds(
                event.getPlayer().getUniqueId()
        ) <= 0) {
            event.disallow(
                    PlayerLoginEvent.Result.KICK_OTHER,
                    limitReachedMessage()
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(
                plugin,
                () -> handleOnlinePlayer(event.getPlayer())
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        hideBossBar(event.getPlayer());
        saveData();
    }

    private void handleOnlinePlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }

        ensureDailyReset();

        if (!enabled) {
            hideBossBar(player);
            return;
        }

        if (getRemainingSeconds(player.getUniqueId()) <= 0) {
            player.kick(limitReachedMessage());
            return;
        }

        updateBossBar(player);
    }

    private void ensureDailyReset() {
        LocalDate today = LocalDate.now(resetZone);

        if (today.equals(storedDate)) {
            return;
        }

        storedDate = today;
        usedSeconds.clear();
        dirty = true;
        saveData();

        for (Player player : Bukkit.getOnlinePlayers()) {
            updateBossBar(player);
        }
    }

    public int getRemainingSeconds(UUID uuid) {
        return Math.max(
                0,
                dailySeconds - usedSeconds.getOrDefault(uuid, 0)
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean newState) {
        enabled = newState;
        plugin.getConfig().set("playtime.enabled", newState);
        plugin.saveConfig();

        for (Player player : Bukkit.getOnlinePlayers()) {
            handleOnlinePlayer(player);
        }
    }

    private void updateBossBar(Player player) {
        UUID uuid = player.getUniqueId();

        if (!enabled || hiddenBossBars.contains(uuid)) {
            hideBossBar(player);
            return;
        }

        int remaining = getRemainingSeconds(uuid);
        BossBar bossBar = bossBars.computeIfAbsent(
                uuid,
                ignored -> Bukkit.createBossBar(
                        "",
                        BarColor.GREEN,
                        BarStyle.SOLID
                )
        );

        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }

        bossBar.setTitle(
                "Daily Playtime: "
                        + formatTime(remaining)
                        + " remaining"
        );
        bossBar.setProgress(Math.max(
                0.0D,
                Math.min(1.0D, remaining / (double) dailySeconds)
        ));

        if (remaining <= 300) {
            bossBar.setColor(BarColor.RED);
        } else if (remaining <= 900) {
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setColor(BarColor.GREEN);
        }

        bossBar.setVisible(true);
    }

    private void hideBossBar(Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());

        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    private void setBossBarVisible(Player player, boolean visible) {
        if (visible) {
            hiddenBossBars.remove(player.getUniqueId());
            updateBossBar(player);
        } else {
            hiddenBossBars.add(player.getUniqueId());
            hideBossBar(player);
        }

        dirty = true;
        saveData();
    }

    private String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private Component limitReachedMessage() {
        return Component.text(
                "You have used today's 1 hour of playtime. "
                        + "You can play again after 00:00.",
                NamedTextColor.RED
        );
    }

    private void saveData() {
        if (!dirty) {
            return;
        }

        data.set("date", storedDate.toString());
        data.set("used-seconds", null);

        for (Map.Entry<UUID, Integer> entry : usedSeconds.entrySet()) {
            data.set(
                    "used-seconds." + entry.getKey(),
                    entry.getValue()
            );
        }

        data.set(
                "hidden-boss-bars",
                hiddenBossBars.stream()
                        .map(UUID::toString)
                        .sorted()
                        .toList()
        );

        try {
            data.save(dataFile);
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Could not save playtime.yml: "
                            + exception.getMessage()
            );
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0
                || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        if (subCommand.equals("show") || subCommand.equals("hide")) {
            handleVisibility(sender, label, subCommand);
            return true;
        }

        if (subCommand.equals("enable")
                || subCommand.equals("disable")) {
            handleAdminToggle(sender, subCommand);
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /"
                        + label
                        + " <show|hide|enable|disable|status>",
                NamedTextColor.RED
        ));
        return true;
    }

    private void handleVisibility(
            CommandSender sender,
            String label,
            String subCommand
    ) {
        if (!sender.hasPermission(PLAYER_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You do not have permission to do that.",
                    NamedTextColor.RED
            ));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(
                    "Only players can use /"
                            + label
                            + " "
                            + subCommand
                            + ".",
                    NamedTextColor.RED
            ));
            return;
        }

        boolean visible = subCommand.equals("show");
        setBossBarVisible(player, visible);
        player.sendMessage(Component.text(
                visible
                        ? "The playtime boss bar is now visible."
                        : "The playtime boss bar is now hidden.",
                NamedTextColor.GREEN
        ));
    }

    private void handleAdminToggle(
            CommandSender sender,
            String subCommand
    ) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You do not have permission to do that.",
                    NamedTextColor.RED
            ));
            return;
        }

        boolean newState = subCommand.equals("enable");
        setEnabled(newState);
        sender.sendMessage(Component.text(
                newState
                        ? "The daily 1-hour playtime limit is now enabled."
                        : "The daily playtime limit is now disabled.",
                newState
                        ? NamedTextColor.GREEN
                        : NamedTextColor.YELLOW
        ));
    }

    private void sendStatus(CommandSender sender) {
        if (!enabled) {
            sender.sendMessage(Component.text(
                    "The daily playtime limit is disabled.",
                    NamedTextColor.YELLOW
            ));
            return;
        }

        if (sender instanceof Player player) {
            sender.sendMessage(Component.text(
                    "Remaining playtime today: "
                            + formatTime(getRemainingSeconds(
                                    player.getUniqueId()
                            )),
                    NamedTextColor.GREEN
            ));
        } else {
            sender.sendMessage(Component.text(
                    "The daily 1-hour playtime limit is enabled.",
                    NamedTextColor.GREEN
            ));
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> options = new ArrayList<>();

        if (sender.hasPermission(PLAYER_PERMISSION)) {
            options.add("show");
            options.add("hide");
            options.add("status");
        }

        if (sender.hasPermission(ADMIN_PERMISSION)) {
            options.add("enable");
            options.add("disable");
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }
}
