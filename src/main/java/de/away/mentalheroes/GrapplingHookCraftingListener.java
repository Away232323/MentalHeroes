package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
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
