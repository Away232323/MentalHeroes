package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class GrapplingHookItems {

    private static final String ITEM_KIND_HOOK = "hook";
    private static final String ITEM_KIND_HEAD = "head";

    private final MentalHeroesPlugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey tierKey;
    private final Map<GrapplingHookTier, NamespacedKey> headRecipeKeys =
            new EnumMap<>(GrapplingHookTier.class);
    private final Map<GrapplingHookTier, NamespacedKey> hookRecipeKeys =
            new EnumMap<>(GrapplingHookTier.class);

    private NamespacedKey netheriteRecipeKey;

    public GrapplingHookItems(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "grappling_kind");
        this.tierKey = new NamespacedKey(plugin, "grappling_tier");
    }

    public void registerRecipes() {
        for (GrapplingHookTier tier : GrapplingHookTier.values()) {
            if (tier == GrapplingHookTier.NETHERITE) {
                continue;
            }

            registerHeadRecipe(tier);
            registerHookRecipe(tier);
        }

        registerNetheriteRecipe();
    }

    private void registerHeadRecipe(GrapplingHookTier tier) {
        NamespacedKey key = new NamespacedKey(
                plugin,
                tier.id() + "_grappling_head"
        );

        ShapedRecipe recipe = new ShapedRecipe(key, createHead(tier));
        recipe.shape(
                ".MM",
                ".NM",
                "..."
        );
        recipe.setIngredient('M', tier.ingredient());
        recipe.setIngredient('N', Material.IRON_NUGGET);

        plugin.getServer().addRecipe(recipe);
        headRecipeKeys.put(tier, key);
    }

    private void registerHookRecipe(GrapplingHookTier tier) {
        NamespacedKey key = new NamespacedKey(
                plugin,
                tier.id() + "_grappling_hook"
        );

        ShapedRecipe recipe = new ShapedRecipe(key, createHook(tier));
        recipe.shape(
                "..H",
                ".C.",
                "S.."
        );
        recipe.setIngredient(
                'H',
                new RecipeChoice.ExactChoice(createHead(tier))
        );
        recipe.setIngredient('C', Material.CHAIN);
        recipe.setIngredient('S', Material.STICK);

        plugin.getServer().addRecipe(recipe);
        hookRecipeKeys.put(tier, key);
    }

    private void registerNetheriteRecipe() {
        netheriteRecipeKey = new NamespacedKey(
                plugin,
                "netherite_grappling_hook"
        );

        SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                netheriteRecipeKey,
                createHook(GrapplingHookTier.NETHERITE),
                new RecipeChoice.MaterialChoice(
                        Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE
                ),
                new RecipeChoice.ExactChoice(
                        createHook(GrapplingHookTier.DIAMOND)
                ),
                new RecipeChoice.MaterialChoice(
                        Material.NETHERITE_INGOT
                )
        );

        plugin.getServer().addRecipe(recipe);
    }

    public ItemStack createHead(GrapplingHookTier tier) {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();

        applyHeadAppearance(meta, tier);
        tag(meta, ITEM_KIND_HEAD, tier);
        item.setItemMeta(meta);
        return item;
    }

    private void applyHeadAppearance(
            ItemMeta meta,
            GrapplingHookTier tier
    ) {
        Component name = Component.text(
                        tier.displayName() + " Grappling Hook Head",
                        tier.textColor()
                )
                .decoration(TextDecoration.ITALIC, false);

        meta.itemName(name);
        meta.setItemModel(
                new NamespacedKey(
                        plugin,
                        tier.id() + "_grappling_head"
                )
        );
    }

    public ItemStack createHook(GrapplingHookTier tier) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta rawMeta = item.getItemMeta();

        applyHookAppearance(rawMeta, tier);
        tag(rawMeta, ITEM_KIND_HOOK, tier);

        if (rawMeta instanceof Damageable damageable) {
            damageable.setDamage(0);
        }

        item.setItemMeta(rawMeta);
        return item;
    }

    private void applyHookAppearance(
            ItemMeta rawMeta,
            GrapplingHookTier tier
    ) {
        Component name = Component.text(
                        tier.displayName() + " Grappling Hook",
                        tier.textColor()
                )
                .decoration(TextDecoration.ITALIC, false);

        rawMeta.itemName(name);
        rawMeta.lore(List.of(
                loreLine("Right-click: Fire / retract hook"),
                loreLine("F: Pull toward the hook"),
                loreLine("No fall damage while attached"),
                Component.text(
                                "Durability: " + tier.durability(),
                                NamedTextColor.DARK_GRAY
                        )
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(
                                "Only Unbreaking can be applied",
                                NamedTextColor.DARK_GRAY
                        )
                        .decoration(TextDecoration.ITALIC, false)
        ));

        rawMeta.setItemModel(
                new NamespacedKey(
                        plugin,
                        tier.id() + "_grappling_hook"
                )
        );

        if (rawMeta instanceof Damageable damageable) {
            damageable.setMaxDamage(tier.durability());
        }
    }

    public void refreshAppearance(ItemStack item) {
        GrapplingHookTier tier = getTier(item);

        if (tier == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();

        if (isHook(item)) {
            applyHookAppearance(meta, tier);
        } else if (isHead(item)) {
            applyHeadAppearance(meta, tier);
        } else {
            return;
        }

        item.setItemMeta(meta);
    }

    public ItemStack createChainLink(GrapplingHookTier tier) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        meta.setItemModel(
                new NamespacedKey(
                        plugin,
                        tier.id() + "_grappling_chain"
                )
        );

        item.setItemMeta(meta);
        return item;
    }

    private Component loreLine(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private void tag(
            ItemMeta meta,
            String kind,
            GrapplingHookTier tier
    ) {
        PersistentDataContainer data =
                meta.getPersistentDataContainer();

        data.set(kindKey, PersistentDataType.STRING, kind);
        data.set(tierKey, PersistentDataType.STRING, tier.id());
    }

    public boolean isHook(ItemStack item) {
        return hasKind(item, ITEM_KIND_HOOK);
    }

    public boolean isHead(ItemStack item) {
        return hasKind(item, ITEM_KIND_HEAD);
    }

    private boolean hasKind(ItemStack item, String expectedKind) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String kind = item.getItemMeta()
                .getPersistentDataContainer()
                .get(kindKey, PersistentDataType.STRING);

        return expectedKind.equals(kind);
    }

    public GrapplingHookTier getTier(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        String id = item.getItemMeta()
                .getPersistentDataContainer()
                .get(tierKey, PersistentDataType.STRING);

        return GrapplingHookTier.fromId(id);
    }

    public GrapplingHookTier getHeadRecipeTier(
            NamespacedKey recipeKey
    ) {
        return findTier(headRecipeKeys, recipeKey);
    }

    public GrapplingHookTier getHookRecipeTier(
            NamespacedKey recipeKey
    ) {
        return findTier(hookRecipeKeys, recipeKey);
    }

    private GrapplingHookTier findTier(
            Map<GrapplingHookTier, NamespacedKey> keys,
            NamespacedKey recipeKey
    ) {
        for (Map.Entry<GrapplingHookTier, NamespacedKey> entry
                : keys.entrySet()) {
            if (entry.getValue().equals(recipeKey)) {
                return entry.getKey();
            }
        }

        return null;
    }

    public NamespacedKey getNetheriteRecipeKey() {
        return netheriteRecipeKey;
    }
}
