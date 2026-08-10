package de.away.mentalheroes;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HeartManager {

    private final MentalHeroesPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Integer> playerHearts = new HashMap<>();

    private YamlConfiguration data;

    public HeartManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");

        load();
    }

    private void load() {
        data = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection playerSection =
                data.getConfigurationSection("players");

        if (playerSection == null) {
            return;
        }

        for (String uuidText : playerSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                int hearts = playerSection.getInt(uuidText);

                playerHearts.put(uuid, clamp(hearts));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(
                        "Invalid UUID in data.yml: " + uuidText
                );
            }
        }
    }

    public int getHearts(UUID uuid) {
        return playerHearts.computeIfAbsent(
                uuid,
                ignored -> getStartingHearts()
        );
    }

    public void setHearts(UUID uuid, int hearts) {
        playerHearts.put(uuid, clamp(hearts));
        save();
    }

    public int removeHeart(UUID uuid) {
        int newAmount = Math.max(0, getHearts(uuid) - 1);

        setHearts(uuid, newAmount);
        return newAmount;
    }

    public int getMaximumHearts() {
        return plugin.getConfig().getInt("hearts.maximum", 3);
    }

    public int getStartingHearts() {
        return Math.min(
                getMaximumHearts(),
                plugin.getConfig().getInt("hearts.starting-hearts", 3)
        );
    }

    private int clamp(int hearts) {
        return Math.max(0, Math.min(getMaximumHearts(), hearts));
    }

    public void save() {
        data.set("players", null);

        for (Map.Entry<UUID, Integer> entry : playerHearts.entrySet()) {
            data.set(
                    "players." + entry.getKey(),
                    entry.getValue()
            );
        }

        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "The data.yml file could not be saved!"
            );
            exception.printStackTrace();
        }
    }
}
