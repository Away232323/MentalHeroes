package de.away.mentalheroes;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class HudManager {

    private static final Key HEART_FONT =
            Key.key("mentalheroes", "hearts");
    private static final Key SPACING_FONT =
            Key.key("mentalheroes", "hud_spacing");

    private static final int HEART_DISPLAY_WIDTH = 25;

    private final MentalHeroesPlugin plugin;
    private final HeartManager heartManager;
    private final CombatManager combatManager;

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
        if (!plugin.isHeroesWorld(player)) {
            player.sendActionBar(Component.empty());
            return;
        }

        int hearts = heartManager.getHearts(player.getUniqueId());
        Component heartDisplay = createHeartDisplay(hearts);

        if (!combatManager.isInCombat(player.getUniqueId())) {
            player.sendActionBar(heartDisplay);
            return;
        }

        int seconds = combatManager.getRemainingSeconds(
                player.getUniqueId()
        );

        String combatPrefix = "IN COMBAT!";
        String combatSuffix = " (" + seconds + "s REMAINING)";

        Component combatDisplay = Component.text(
                        combatPrefix,
                        NamedTextColor.RED
                )
                .decorate(TextDecoration.BOLD)
                .append(
                        Component.text(
                                combatSuffix,
                                NamedTextColor.GRAY
                        ).decoration(TextDecoration.BOLD, false)
                );

        int combatWidth = textWidth(combatPrefix, true)
                + textWidth(combatSuffix, false);

        int beforeCombat = Math.floorDiv(
                HEART_DISPLAY_WIDTH - combatWidth,
                2
        );
        int afterCombat = -beforeCombat - combatWidth;

        Component fixedHud = Component.empty()
                .append(spacing(beforeCombat))
                .append(combatDisplay)
                .append(spacing(afterCombat))
                .append(heartDisplay);

        player.sendActionBar(fixedHud);
    }

    public void hideCombatBar(Player player) {
        // Kept for listeners that clear HUD state when a player quits.
    }

    private Component spacing(int pixels) {
        if (pixels == 0) {
            return Component.empty();
        }

        boolean negative = pixels < 0;
        int remaining = Math.abs(pixels);
        StringBuilder characters = new StringBuilder();

        for (int bit = 8; bit >= 0; bit--) {
            int value = 1 << bit;

            if (remaining < value) {
                continue;
            }

            char character = (char) (
                    (negative ? 0xE200 : 0xE210) + bit
            );

            characters.append(character);
            remaining -= value;
        }

        return Component.text(characters.toString()).font(SPACING_FONT);
    }

    private int textWidth(String text, boolean bold) {
        int width = 0;

        for (char character : text.toCharArray()) {
            width += characterWidth(character);

            if (bold && character != ' ') {
                width++;
            }
        }

        return width;
    }

    private int characterWidth(char character) {
        return switch (character) {
            case ' ' -> 4;
            case '!', 'i', '.', ',', ':', ';', '|' -> 2;
            case 'I' -> 4;
            case '(', ')' -> 5;
            default -> 6;
        };
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
