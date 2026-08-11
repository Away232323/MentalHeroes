package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrialChamberManager implements Listener {

    private final NamespacedKey processedKey;

    public TrialChamberManager(MentalHeroesPlugin plugin) {
        processedKey = new NamespacedKey(
                plugin,
                "trial_chamber_removed"
        );
    }

    public void removeFromLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                removeTrialChamber(chunk);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        removeTrialChamber(event.getChunk());
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onStructureSpawn(AsyncStructureSpawnEvent event) {
        if (event.getStructure().equals(Structure.TRIAL_CHAMBERS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();

        if (type != Material.TRIAL_SPAWNER
                && type != Material.VAULT) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text(
                "Trial Chamber blocks are disabled.",
                NamedTextColor.RED
        ));
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!isTrialStructureCommand(event.getMessage())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "Trial Chambers are disabled.",
                NamedTextColor.RED
        ));
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onServerCommand(ServerCommandEvent event) {
        if (!isTrialStructureCommand(event.getCommand())) {
            return;
        }

        event.setCancelled(true);
        event.getSender().sendMessage(
                "Trial Chambers are disabled."
        );
    }

    private void removeTrialChamber(Chunk chunk) {
        if (chunk.getWorld().getEnvironment()
                != World.Environment.NORMAL) {
            return;
        }

        if (chunk.getPersistentDataContainer().has(
                processedKey,
                PersistentDataType.BYTE
        )) {
            return;
        }

        List<BoundingBox> pieces = new ArrayList<>();

        for (GeneratedStructure generatedStructure
                : chunk.getStructures(Structure.TRIAL_CHAMBERS)) {
            for (StructurePiece piece : generatedStructure.getPieces()) {
                pieces.add(piece.getBoundingBox());
            }

            if (generatedStructure.getPieces().isEmpty()) {
                pieces.add(generatedStructure.getBoundingBox());
            }
        }

        if (pieces.isEmpty()) {
            return;
        }

        World world = chunk.getWorld();
        removeEntities(chunk.getEntities(), world, pieces);

        int chunkMinX = chunk.getX() << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunk.getZ() << 4;
        int chunkMaxZ = chunkMinZ + 15;

        for (BoundingBox piece : pieces) {
            int minX = (int) Math.ceil(Math.max(
                    piece.getMinX(),
                    chunkMinX
            ));
            int maxX = (int) Math.floor(Math.min(
                    piece.getMaxX(),
                    chunkMaxX
            ));
            int minY = (int) Math.ceil(Math.max(
                    piece.getMinY(),
                    world.getMinHeight()
            ));
            int maxY = (int) Math.floor(Math.min(
                    piece.getMaxY(),
                    world.getMaxHeight() - 1
            ));
            int minZ = (int) Math.ceil(Math.max(
                    piece.getMinZ(),
                    chunkMinZ
            ));
            int maxZ = (int) Math.floor(Math.min(
                    piece.getMaxZ(),
                    chunkMaxZ
            ));

            if (minX > maxX || minY > maxY || minZ > maxZ) {
                continue;
            }

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Material replacement = y < 0
                                ? Material.DEEPSLATE
                                : Material.STONE;
                        world.getBlockAt(x, y, z).setType(
                                replacement,
                                false
                        );
                    }
                }
            }
        }

        chunk.getPersistentDataContainer().set(
                processedKey,
                PersistentDataType.BYTE,
                (byte) 1
        );
    }

    private void removeEntities(
            Entity[] entities,
            World world,
            List<BoundingBox> pieces
    ) {
        for (Entity entity : entities) {
            var location = entity.getLocation();
            boolean insideTrialChamber = pieces.stream().anyMatch(
                    piece -> piece.contains(
                            location.getX(),
                            location.getY(),
                            location.getZ()
                    )
            );

            if (!insideTrialChamber) {
                continue;
            }

            if (entity instanceof Player player) {
                player.teleport(world.getSpawnLocation());
            } else {
                entity.remove();
            }
        }
    }

    private boolean isTrialStructureCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);

        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        boolean structureCommand = normalized.startsWith(
                "locate structure "
        ) || normalized.startsWith(
                "minecraft:locate structure "
        ) || normalized.startsWith(
                "place structure "
        ) || normalized.startsWith(
                "minecraft:place structure "
        );

        return structureCommand && normalized.contains(
                "trial_chambers"
        );
    }
}
