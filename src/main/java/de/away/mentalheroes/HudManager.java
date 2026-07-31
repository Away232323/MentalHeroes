package de.away.mentalheroes;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class HudManager {

    private static final Key HEART_FONT =
            Key.key("mentalheroes", "hearts");

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

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
    }

    public void update(Player player) {
        int hearts = heartManager.getHearts(player.getUniqueId());
        Component heartDisplay = createHeartDisplay(hearts);

        if (!combatManager.isInCombat(player.getUniqueId())) {
            player.sendActionBar(heartDisplay);
            return;
        }

        int seconds = combatManager.getRemainingSeconds(
                player.getUniqueId()
        );

        Component combatDisplay = miniMessage.deserialize(
                "<red><bold>Im Kampf!</bold></red> "
                        + "<gray>(<white>"
                        + seconds
                        + "s"
                        + "</white> übrig)</gray> "
        );

        player.sendActionBar(
                combatDisplay.append(heartDisplay)
        );
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
