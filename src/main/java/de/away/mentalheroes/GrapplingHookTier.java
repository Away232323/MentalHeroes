package de.away.mentalheroes;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

public enum GrapplingHookTier {

    COPPER(
            "copper",
            "Copper",
            Material.COPPER_INGOT,
            250,
            NamedTextColor.GOLD,
            Color.fromRGB(199, 105, 66)
    ),
    GOLD(
            "gold",
            "Gold",
            Material.GOLD_INGOT,
            250,
            NamedTextColor.YELLOW,
            Color.fromRGB(255, 210, 58)
    ),
    IRON(
            "iron",
            "Iron",
            Material.IRON_INGOT,
            500,
            NamedTextColor.WHITE,
            Color.fromRGB(205, 211, 217)
    ),
    DIAMOND(
            "diamond",
            "Diamond",
            Material.DIAMOND,
            15_000,
            NamedTextColor.AQUA,
            Color.fromRGB(55, 218, 229)
    ),
    NETHERITE(
            "netherite",
            "Netherite",
            Material.NETHERITE_INGOT,
            25_000,
            NamedTextColor.DARK_PURPLE,
            Color.fromRGB(108, 82, 98)
    );

    private final String id;
    private final String displayName;
    private final Material ingredient;
    private final int durability;
    private final NamedTextColor textColor;
    private final Color particleColor;

    GrapplingHookTier(
            String id,
            String displayName,
            Material ingredient,
            int durability,
            NamedTextColor textColor,
            Color particleColor
    ) {
        this.id = id;
        this.displayName = displayName;
        this.ingredient = ingredient;
        this.durability = durability;
        this.textColor = textColor;
        this.particleColor = particleColor;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material ingredient() {
        return ingredient;
    }

    public int durability() {
        return durability;
    }

    public NamedTextColor textColor() {
        return textColor;
    }

    public Color particleColor() {
        return particleColor;
    }

    public static GrapplingHookTier fromId(String id) {
        if (id == null) {
            return null;
        }

        String normalized = id.toLowerCase(Locale.ROOT);

        for (GrapplingHookTier tier : values()) {
            if (tier.id.equals(normalized)) {
                return tier;
            }
        }

        return null;
    }
}
