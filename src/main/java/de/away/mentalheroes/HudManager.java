package de.away.mentalheroes;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HudManager {

    private static final Key HEART_FONT =
            Key.key("mentalheroes", "hearts");

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, BossBar> combatBars = new HashMap<>();

    private BukkitTask hudTask;

    public HudManager(
            MentalHeroesPlugin plugin,
            HeartManager heartManager,
            CombatManager combatManager
    ) {
        this.plugin = plugin;
        this.heartManager = heartManager;
        this.combatManager = combatManager;
    }

    public void start() {
        hudTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        update(player);
                    }
                },
                5L,
                5L
        );
    }

    public void stop() {
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }

        for (Map.Entry<UUID, BossBar> entry : combatBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }

        combatBars.clear();
    }

    public void update(Player player) {
        int hearts = heartManager.getHearts(player.getUniqueId());
        Component heartDisplay = createHeartDisplay(hearts);

        // Hearts always stay in their own centered action bar.
        player.sendActionBar(heartDisplay);

        if (!combatManager.isInCombat(player.getUniqueId())) {
            hideCombatBar(player);
            return;
        }

        int seconds = combatManager.getRemainingSeconds(
                player.getUniqueId()
        );

        Component combatDisplay = miniMessage.deserialize(
                "<red><bold>IN COMBAT</bold></red> "
                        + "<gray>• <white>"
                        + seconds
                        + "s REMAINING</white></gray>"
        );

        int maximumSeconds = Math.max(
                1,
                plugin.getConfig().getInt(
                        "combat.maximum-seconds",
                        150
                )
        );

        float progress = Math.max(
                0.0F,
                Math.min(1.0F, (float) seconds / maximumSeconds)
        );

        BossBar combatBar = combatBars.computeIfAbsent(
                player.getUniqueId(),
                ignored -> BossBar.bossBar(
                        combatDisplay,
                        progress,
                        BossBar.Color.RED,
                        BossBar.Overlay.PROGRESS
                )
        );

        combatBar.name(combatDisplay);
        combatBar.progress(progress);
        player.showBossBar(combatBar);
    }

    public void hideCombatBar(Player player) {
        BossBar combatBar = combatBars.remove(player.getUniqueId());

        if (combatBar != null) {
            player.hideBossBar(combatBar);
        }
    }

    private Component createHeartDisplay(int hearts) {
        String character = switch (hearts) {
            case 0 -> "\uE000";
            case 1 -> "\uE001";
            case 2 -> "\uE002";
            default -> "\uE003";
        };

        return Component.text(character).font(HEART_FONT);
    }
}
