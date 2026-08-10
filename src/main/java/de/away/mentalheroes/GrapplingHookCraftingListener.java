package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.Iterator;
import java.util.Map;

public final class GrapplingHookCraftingListener
        implements Listener {

    private final GrapplingHookItems items;

    public GrapplingHookCraftingListener(
            GrapplingHookItems items
    ) {
        this.items = items;
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }

        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();

        GrapplingHookTier headTier =
                items.getHeadRecipeTier(keyed.getKey());

        if (headTier != null) {
            if (!hasAmount(matrix, 4, Material.STRING, 2)) {
                inventory.setResult(null);
            }
            return;
        }

        GrapplingHookTier hookTier =
                items.getHookRecipeTier(keyed.getKey());

        if (hookTier == null) {
            return;
        }

        if (!hasAmount(matrix, 4, Material.CHAIN, 2)
                || !hasAmount(
                        matrix,
                        6,
                        Material.STICK,
                        2
                )) {
            inventory.setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }

        GrapplingHookTier headTier =
                items.getHeadRecipeTier(keyed.getKey());
        GrapplingHookTier hookTier =
                items.getHookRecipeTier(keyed.getKey());

        if (headTier == null && hookTier == null) {
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(
                    Component.text(
                            "Greifhaken bitte einzeln craften.",
                            NamedTextColor.RED
                    )
            );
            return;
        }

        CraftingInventory inventory =
                (CraftingInventory) event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();

        if (headTier != null) {
            if (!hasAmount(matrix, 4, Material.STRING, 2)) {
                event.setCancelled(true);
                return;
            }

            consumeExtra(matrix, 4);
            inventory.setMatrix(matrix);
            return;
        }

        if (!hasAmount(matrix, 4, Material.CHAIN, 2)
                || !hasAmount(
                        matrix,
                        6,
                        Material.STICK,
                        2
                )) {
            event.setCancelled(true);
            return;
        }

        consumeExtra(matrix, 4);
        consumeExtra(matrix, 6);
        inventory.setMatrix(matrix);
    }

    private boolean hasAmount(
            ItemStack[] matrix,
            int slot,
            Material material,
            int amount
    ) {
        if (slot < 0 || slot >= matrix.length) {
            return false;
        }

        ItemStack item = matrix[slot];

        return item != null
                && item.getType() == material
                && item.getAmount() >= amount;
    }

    private void consumeExtra(ItemStack[] matrix, int slot) {
        ItemStack item = matrix[slot];

        if (item == null) {
            return;
        }

        item.setAmount(item.getAmount() - 1);
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        ItemStack template = inventory.getItem(0);
        ItemStack base = inventory.getItem(1);
        ItemStack addition = inventory.getItem(2);

        if (template == null
                || template.getType()
                != Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE
                || addition == null
                || addition.getType() != Material.NETHERITE_INGOT
                || items.getTier(base) != GrapplingHookTier.DIAMOND
                || !items.isHook(base)) {
            return;
        }

        ItemStack result = items.createHook(
                GrapplingHookTier.NETHERITE
        );

        copyDurabilityAndUnbreaking(base, result);
        event.setResult(result);
    }

    private void copyDurabilityAndUnbreaking(
            ItemStack source,
            ItemStack target
    ) {
        if (source.getItemMeta() instanceof Damageable sourceDamage
                && target.getItemMeta()
                instanceof Damageable targetDamage) {
            int sourceMaximum = sourceDamage.hasMaxDamage()
                    ? sourceDamage.getMaxDamage()
                    : source.getType().getMaxDurability();

            double usedRatio = sourceMaximum <= 0
                    ? 0.0D
                    : (double) sourceDamage.getDamage()
                    / sourceMaximum;

            int targetMaximum = targetDamage.getMaxDamage();
            int targetDamageValue = (int) Math.round(
                    targetMaximum * usedRatio
            );

            targetDamage.setDamage(
                    Math.min(
                            targetMaximum - 1,
                            Math.max(0, targetDamageValue)
                    )
            );
            target.setItemMeta(targetDamage);
        }

        int unbreaking = source.getEnchantmentLevel(
                Enchantment.UNBREAKING
        );

        if (unbreaking > 0) {
            target.addUnsafeEnchantment(
                    Enchantment.UNBREAKING,
                    unbreaking
            );
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        if (!items.isHook(event.getItem())) {
            return;
        }

        Iterator<Map.Entry<Enchantment, Integer>> iterator =
                event.getEnchantsToAdd()
                        .entrySet()
                        .iterator();

        while (iterator.hasNext()) {
            Map.Entry<Enchantment, Integer> entry =
                    iterator.next();

            if (!entry.getKey().equals(
                    Enchantment.UNBREAKING
            )) {
                iterator.remove();
            }
        }

        if (!event.getEnchantsToAdd().isEmpty()) {
            return;
        }

        event.setCancelled(true);
        event.getEnchanter().sendMessage(
                Component.text(
                        "Greifhaken können nur Haltbarkeit erhalten.",
                        NamedTextColor.RED
                )
        );
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();

        if (!items.isHook(result)) {
            return;
        }

        boolean forbidden = result.getEnchantments()
                .keySet()
                .stream()
                .anyMatch(
                        enchantment -> !enchantment.equals(
                                Enchantment.UNBREAKING
                        )
                );

        if (forbidden) {
            event.setResult(null);
        }
    }

    @EventHandler
    public void onMend(PlayerItemMendEvent event) {
        if (items.isHook(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
