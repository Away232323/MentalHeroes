package de.away.mentalheroes;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;

/**
 * Replaces the normal 8-gold Golden Apple recipe with a cheaper
 * 4-gold cross recipe while the player is inside MentalHEROS.
 * Other modes keep normal Minecraft crafting behaviour.
 */
final class HeroesGoldenAppleRecipeManager implements Listener {

    private final MentalHeroesPlugin plugin;
    private final NamespacedKey recipeKey;

    HeroesGoldenAppleRecipeManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "heroes_golden_apple");
    }

    void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, new ItemStack(Material.GOLDEN_APPLE));
        recipe.shape(
                " G ",
                "GAG",
                " G "
        );
        recipe.setIngredient('G', Material.GOLD_INGOT);
        recipe.setIngredient('A', Material.APPLE);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        HumanEntity viewer = event.getView().getPlayer();
        if (!(viewer instanceof Player player)) {
            return;
        }

        Recipe recipe = event.getRecipe();
        boolean heroesRecipe = isHeroesRecipe(recipe);

        if (heroesRecipe && !plugin.isHeroesWorld(player)) {
            event.getInventory().setResult(null);
            return;
        }

        if (plugin.isHeroesWorld(player)
                && !heroesRecipe
                && isVanillaEightGoldRecipe(event.getInventory())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        boolean heroesRecipe = isHeroesRecipe(event.getRecipe());

        if (heroesRecipe && !plugin.isHeroesWorld(player)) {
            event.setCancelled(true);
            return;
        }

        if (plugin.isHeroesWorld(player)
                && !heroesRecipe
                && isVanillaEightGoldRecipe(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    private boolean isHeroesRecipe(Recipe recipe) {
        return recipe instanceof Keyed keyed && keyed.getKey().equals(recipeKey);
    }

    private boolean isVanillaEightGoldRecipe(CraftingInventory inventory) {
        ItemStack[] matrix = inventory.getMatrix();
        int gold = 0;
        int apples = 0;
        int other = 0;

        for (ItemStack item : matrix) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getType() == Material.GOLD_INGOT) {
                gold++;
            } else if (item.getType() == Material.APPLE) {
                apples++;
            } else {
                other++;
            }
        }

        return gold == 8 && apples == 1 && other == 0;
    }
}
