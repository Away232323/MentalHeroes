package de.away.mentalheroes;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class MentalHeroesPlugin extends JavaPlugin {

    private HeartManager heartManager;
    private CombatManager combatManager;
    private HudManager hudManager;
    private GrapplingHookManager grapplingHookManager;
    private HeartLossAnimationManager heartLossAnimationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateEnglishMessages();

        heartManager = new HeartManager(this);
        combatManager = new CombatManager(this);
        hudManager = new HudManager(this, heartManager, combatManager);

        GrapplingHookItems grapplingHookItems =
                new GrapplingHookItems(this);
        grapplingHookItems.registerRecipes();

        grapplingHookManager = new GrapplingHookManager(
                this,
                grapplingHookItems
        );

        heartLossAnimationManager = new HeartLossAnimationManager(
                this,
                heartManager,
                combatManager
        );

        DisabledMobListener disabledMobListener =
                new DisabledMobListener();

        HeroListener heroListener = new HeroListener(
                this,
                heartManager,
                combatManager,
                hudManager,
                heartLossAnimationManager
        );

        getServer().getPluginManager().registerEvents(heroListener, this);
        getServer().getPluginManager().registerEvents(
                heartLossAnimationManager,
                this
        );
        getServer().getPluginManager().registerEvents(
                disabledMobListener,
                this
        );
        getServer().getPluginManager().registerEvents(
                grapplingHookManager,
                this
        );
        getServer().getPluginManager().registerEvents(
                new GrapplingHookCraftingListener(
                        grapplingHookItems
                ),
                this
        );

        MentalHeroesCommand commandHandler = new MentalHeroesCommand(
                this,
                heartManager,
                hudManager,
                grapplingHookItems
        );

        PluginCommand command = getCommand("mentalheroes");

        if (command == null) {
            throw new IllegalStateException(
                    "The mentalheroes command is missing from plugin.yml!"
            );
        }

        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        combatManager.start();
        hudManager.start();
        grapplingHookManager.start();
        disabledMobListener.removeExistingMobs();

        getLogger().info("MentalHeroes has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (combatManager != null) {
            combatManager.stop();
        }

        if (hudManager != null) {
            hudManager.stop();
        }

        if (grapplingHookManager != null) {
            grapplingHookManager.stop();
        }

        if (heartLossAnimationManager != null) {
            heartLossAnimationManager.stop();
        }

        if (heartManager != null) {
            heartManager.save();
        }

        getLogger().info("MentalHeroes has been disabled.");
    }

    private void migrateEnglishMessages() {
        Map<String, String[]> migrations = Map.ofEntries(
                Map.entry(
                        "ban.reason",
                        new String[]{
                                "<red><bold>Du hast alle deine Heldenherzen verloren!</bold></red>",
                                "<red><bold>You have lost all of your Hero Hearts!</bold></red>"
                        }
                ),
                Map.entry(
                        "messages.no-permission",
                        new String[]{
                                "<red>Dafür hast du keine Berechtigung.</red>",
                                "<red>You do not have permission to do that.</red>"
                        }
                ),
                Map.entry(
                        "messages.player-not-found",
                        new String[]{
                                "<red>Dieser Spieler wurde nicht gefunden.</red>",
                                "<red>That player could not be found.</red>"
                        }
                ),
                Map.entry(
                        "messages.invalid-number",
                        new String[]{
                                "<red>Die Herzen müssen zwischen 0 und 3 liegen.</red>",
                                "<red>Hearts must be between 0 and 3.</red>"
                        }
                ),
                Map.entry(
                        "messages.own-hearts",
                        new String[]{
                                "<gray>Du besitzt aktuell <aqua><hearts></aqua> Herzen.</gray>",
                                "<gray>You currently have <aqua><hearts></aqua> hearts.</gray>"
                        }
                ),
                Map.entry(
                        "messages.other-hearts",
                        new String[]{
                                "<gray><player> besitzt aktuell <aqua><hearts></aqua> Herzen.</gray>",
                                "<gray><player> currently has <aqua><hearts></aqua> hearts.</gray>"
                        }
                ),
                Map.entry(
                        "messages.hearts-set",
                        new String[]{
                                "<green>Die Herzen von <player> wurden auf <hearts> gesetzt.</green>",
                                "<green>Set <player>'s hearts to <hearts>.</green>"
                        }
                ),
                Map.entry(
                        "messages.hearts-received",
                        new String[]{
                                "<green>Deine Herzen wurden auf <hearts> gesetzt.</green>",
                                "<green>Your hearts were set to <hearts>.</green>"
                        }
                ),
                Map.entry(
                        "messages.heart-lost",
                        new String[]{
                                "<red>Du hast ein blaues Herz verloren! Verbleibend: <hearts></red>",
                                "<red>You lost a blue heart! Remaining: <hearts></red>"
                        }
                ),
                Map.entry(
                        "messages.combat-ended",
                        new String[]{
                                "<green>Du bist nicht mehr im Kampf.</green>",
                                "<green>You are no longer in combat.</green>"
                        }
                )
        );

        boolean changed = false;

        for (Map.Entry<String, String[]> entry : migrations.entrySet()) {
            String[] values = entry.getValue();

            if (values[0].equals(getConfig().getString(entry.getKey()))) {
                getConfig().set(entry.getKey(), values[1]);
                changed = true;
            }
        }

        if (changed) {
            saveConfig();
        }
    }
}
