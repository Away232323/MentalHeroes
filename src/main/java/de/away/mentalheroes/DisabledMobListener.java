package de.away.mentalheroes;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.ChunkLoadEvent;

public final class DisabledMobListener implements Listener {

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isDisabled(event.getEntityType())) {
            return;
        }

        event.setCancelled(true);
        event.getEntity().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (isDisabled(entity.getType())) {
                entity.remove();
            }
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onEntityTransform(EntityTransformEvent event) {
        if (event.getTransformedEntities().stream()
                .anyMatch(entity -> isDisabled(entity.getType()))) {
            event.setCancelled(true);
        }
    }

    public void removeExistingMobs() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isDisabled(entity.getType())) {
                    entity.remove();
                }
            }
        }
    }

    private boolean isDisabled(EntityType type) {
        return type == EntityType.PHANTOM
                || type == EntityType.ENDERMITE
                || type == EntityType.BREEZE
                || type == EntityType.VILLAGER
                || type == EntityType.WANDERING_TRADER
                || type == EntityType.ZOMBIE_VILLAGER;
    }
}
