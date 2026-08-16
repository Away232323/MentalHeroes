package de.away.mentalheroes;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the MentalHeroes grave system authoritative for inventory handling.
 *
 * Some server/world settings or other plugins can enable keepInventory or
 * clear PlayerDeathEvent#getDrops before GraveManager sees the event. In that
 * case the old grave listener intentionally returned and no grave appeared.
 *
 * We snapshot the complete player inventory at LOWEST priority and restore
 * that snapshot as the death-drop list at HIGH priority. GraveManager then
 * runs at HIGHEST and moves those items into the grave. If GraveManager cannot
 * place a grave, Bukkit simply drops the restored items normally, so loot is
 * never silently deleted.
 */
public final class GraveDeathCompatibilityListener implements Listener {

    private final MentalHeroesPlugin plugin;
    private final Map<UUID, List<ItemStack>> snapshots = new HashMap<>();

    public GraveDeathCompatibilityListener(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void snapshotInventory(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isHeroesWorld(player)) {
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        add(items, player.getInventory().getStorageContents());
        add(items, player.getInventory().getArmorContents());
        add(items, player.getInventory().getExtraContents());
        snapshots.put(player.getUniqueId(), items);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void prepareGraveDrops(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isHeroesWorld(player)) {
            snapshots.remove(player.getUniqueId());
            return;
        }

        List<ItemStack> snapshot = snapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            snapshot = List.of();
        }

        // A MentalHeroes death must go through the grave system even when the
        // world or another plugin had keepInventory enabled.
        event.setKeepInventory(false);

        // Use our early snapshot instead of trusting a possibly emptied or
        // partially modified death-drop list.
        event.getDrops().clear();
        for (ItemStack item : snapshot) {
            event.getDrops().add(item.clone());
        }
    }

    private void add(List<ItemStack> target, ItemStack[] source) {
        if (source == null) {
            return;
        }
        for (ItemStack item : source) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                target.add(item.clone());
            }
        }
    }
}
