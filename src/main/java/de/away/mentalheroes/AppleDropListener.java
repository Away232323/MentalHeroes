package de.away.mentalheroes;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public final class AppleDropListener implements Listener {

    private final MentalHeroesPlugin plugin;

    public AppleDropListener(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onLeafBreak(BlockBreakEvent event) {
        if (!plugin.isHeroesWorld(event.getPlayer())
                || !event.isDropItems()
                || !Tag.LEAVES.isTagged(event.getBlock().getType())
                || event.getPlayer().getGameMode()
                == GameMode.CREATIVE) {
            return;
        }

        ItemStack tool = event.getPlayer()
                .getInventory()
                .getItemInMainHand();

        if (tool.getType() == Material.SHEARS
                || tool.containsEnchantment(
                        Enchantment.SILK_TOUCH
                )) {
            return;
        }

        double baseChance = plugin.getConfig().getDouble(
                "apple-drops.base-chance",
                0.10D
        );
        double fortuneBonus = plugin.getConfig().getDouble(
                "apple-drops.fortune-bonus-per-level",
                0.015D
        );
        int fortuneLevel = tool.getEnchantmentLevel(
                Enchantment.FORTUNE
        );
        double chance = Math.max(
                0.0D,
                Math.min(
                        1.0D,
                        baseChance + (fortuneLevel * fortuneBonus)
                )
        );

        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        Location dropLocation = event.getBlock()
                .getLocation()
                .add(0.5D, 0.5D, 0.5D);

        event.getBlock().getWorld().dropItemNaturally(
                dropLocation,
                new ItemStack(Material.APPLE)
        );
    }
}
