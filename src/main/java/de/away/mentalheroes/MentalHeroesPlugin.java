package de.away.mentalheroes;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MentalHeroesPlugin extends JavaPlugin {

    private HeartManager heartManager;
    private CombatManager combatManager;
    private HudManager hudManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        heartManager = new HeartManager(this);
        combatManager = new CombatManager(this);
        hudManager = new HudManager(this, heartManager, combatManager);

        HeroListener heroListener = new HeroListener(
                this,
                heartManager,
                combatManager,
                hudManager
        );

        getServer().getPluginManager().registerEvents(heroListener, this);

        MentalHeroesCommand commandHandler = new MentalHeroesCommand(
                this,
                heartManager,
                hudManager
        );

        PluginCommand command = getCommand("mentalheroes");

        if (command == null) {
            throw new IllegalStateException(
                    "Der Command mentalheroes fehlt in der plugin.yml!"
            );
        }

        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        combatManager.start();
        hudManager.start();

        getLogger().info("MentalHeroes wurde erfolgreich aktiviert!");
    }

    @Override
    public void onDisable() {
        if (combatManager != null) {
            combatManager.stop();
        }

        if (hudManager != null) {
            hudManager.stop();
        }

        if (heartManager != null) {
            heartManager.save();
        }

        getLogger().info("MentalHeroes wurde deaktiviert.");
    }
}
