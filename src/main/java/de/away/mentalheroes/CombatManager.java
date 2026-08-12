package de.away.mentalheroes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatManager {

    private final MentalHeroesPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, CombatState> combatPlayers = new HashMap<>();

    private BukkitTask timerTask;

    public CombatManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        timerTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                20L
        );
    }

    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        combatPlayers.clear();
    }

    public void tag(Player firstPlayer, Player secondPlayer) {
        if (!plugin.isHeroesWorld(firstPlayer)
                || !plugin.isHeroesWorld(secondPlayer)) {
            return;
        }

        UUID firstUuid = firstPlayer.getUniqueId();
        UUID secondUuid = secondPlayer.getUniqueId();

        if (firstUuid.equals(secondUuid)) {
            return;
        }

        tagPlayer(firstUuid, secondUuid);
        tagPlayer(secondUuid, firstUuid);
    }

    private void tagPlayer(UUID uuid, UUID opponentUuid) {
        CombatState state = combatPlayers.computeIfAbsent(
                uuid,
                ignored -> new CombatState()
        );

        state.opponents.add(opponentUuid);
        state.hitCount++;
        state.remainingSeconds = calculateDuration(state.hitCount);
    }

    private int calculateDuration(int hitCount) {
        int startSeconds = plugin.getConfig().getInt(
                "combat.start-seconds",
                30
        );

        int hitsPerExtension = Math.max(
                1,
                plugin.getConfig().getInt(
                        "combat.hits-per-extension",
                        5
                )
        );

        int extensionSeconds = plugin.getConfig().getInt(
                "combat.extension-seconds",
                20
        );

        int maximumSeconds = Math.max(
                1,
                plugin.getConfig().getInt(
                        "combat.maximum-seconds",
                        150
                )
        );

        int extensions = (hitCount - 1) / hitsPerExtension;

        return Math.min(
                maximumSeconds,
                startSeconds + (extensions * extensionSeconds)
        );
    }

    private void tick() {
        Iterator<Map.Entry<UUID, CombatState>> iterator =
                combatPlayers.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, CombatState> entry = iterator.next();
            CombatState state = entry.getValue();

            Player activePlayer = Bukkit.getPlayer(entry.getKey());
            if (activePlayer == null
                    || !activePlayer.isOnline()
                    || !plugin.isHeroesWorld(activePlayer)) {
                iterator.remove();
                removeOpponentReferences(entry.getKey());
                continue;
            }

            state.remainingSeconds--;

            if (state.remainingSeconds > 0) {
                continue;
            }

            iterator.remove();
            removeOpponentReferences(entry.getKey());

            Player player = Bukkit.getPlayer(entry.getKey());

            if (player != null && player.isOnline()) {
                String prefix = plugin.getConfig().getString(
                        "messages.prefix",
                        ""
                );

                String message = plugin.getConfig().getString(
                        "messages.combat-ended",
                        "<green>You are no longer in combat.</green>"
                );

                Component component = miniMessage.deserialize(
                        prefix + message
                );

                player.sendMessage(component);
            }
        }
    }

    public boolean isInCombat(UUID uuid) {
        return combatPlayers.containsKey(uuid);
    }

    public int getRemainingSeconds(UUID uuid) {
        CombatState state = combatPlayers.get(uuid);

        if (state == null) {
            return 0;
        }

        return Math.max(0, state.remainingSeconds);
    }

    public int getHitCount(UUID uuid) {
        CombatState state = combatPlayers.get(uuid);

        if (state == null) {
            return 0;
        }

        return state.hitCount;
    }

    public boolean areOpponents(UUID firstUuid, UUID secondUuid) {
        CombatState firstState = combatPlayers.get(firstUuid);
        CombatState secondState = combatPlayers.get(secondUuid);

        return firstState != null
                && secondState != null
                && firstState.opponents.contains(secondUuid)
                && secondState.opponents.contains(firstUuid);
    }

    public void clear(UUID uuid) {
        if (combatPlayers.remove(uuid) != null) {
            removeOpponentReferences(uuid);
        }
    }

    private void removeOpponentReferences(UUID uuid) {
        for (CombatState state : combatPlayers.values()) {
            state.opponents.remove(uuid);
        }
    }

    private static final class CombatState {

        private int remainingSeconds;
        private int hitCount;
        private final Set<UUID> opponents = new HashSet<>();
    }
}
