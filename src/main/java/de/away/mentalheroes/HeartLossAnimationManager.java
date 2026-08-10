package de.away.mentalheroes;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HeartLossAnimationManager implements Listener {

    private static final Key ANIMATION_FONT =
            Key.key("mentalheroes", "heart_loss_animation");

    private static final String[] FRAMES = {
            "\uE100", "\uE101", "\uE102", "\uE103",
            "\uE104", "\uE105", "\uE106", "\uE107",
            "\uE108", "\uE109", "\uE10A", "\uE10B"
    };

    private static final long FRAME_TICKS = 2L;

    private final MentalHeroesPlugin plugin;
    private final Set<UUID> pendingAnimations = new HashSet<>();
    private final Set<UUID> animatingPlayers = new HashSet<>();

    public HeartLossAnimationManager(MentalHeroesPlugin plugin) {
        this.plugin = plugin;
    }

    public void queueAnimation(UUID uuid) {
        pendingAnimations.add(uuid);
    }

    public boolean playPendingAnimation(
            Player player,
            Runnable completion
    ) {
        UUID uuid = player.getUniqueId();

        if (!pendingAnimations.remove(uuid)
                || !animatingPlayers.add(uuid)) {
            return false;
        }

        player.clearTitle();

        Title.Times times = Title.Times.times(
                Duration.ZERO,
                Duration.ofMillis(150),
                Duration.ZERO
        );

        for (int index = 0; index < FRAMES.length; index++) {
            String frame = FRAMES[index];
            long delay = index * FRAME_TICKS;

            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> showFrame(player, uuid, frame, times),
                    delay
            );
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> finishAnimation(player, uuid, completion),
                FRAMES.length * FRAME_TICKS + 1L
        );

        return true;
    }

    private void showFrame(
            Player player,
            UUID uuid,
            String frame,
            Title.Times times
    ) {
        if (!player.isOnline() || !animatingPlayers.contains(uuid)) {
            return;
        }

        player.showTitle(
                Title.title(
                        Component.text(frame).font(ANIMATION_FONT),
                        Component.empty(),
                        times
                )
        );

        if (frame.equals("\uE104")) {
            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                    0.9F,
                    0.75F
            );
        }
    }

    private void finishAnimation(
            Player player,
            UUID uuid,
            Runnable completion
    ) {
        if (!animatingPlayers.remove(uuid)) {
            return;
        }

        player.clearTitle();

        if (!player.isOnline()) {
            pendingAnimations.add(uuid);
            return;
        }

        completion.run();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (animatingPlayers.remove(uuid)) {
            pendingAnimations.add(uuid);
        }

        event.getPlayer().clearTitle();
    }

    public void stop() {
        for (UUID uuid : animatingPlayers) {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null) {
                player.clearTitle();
            }
        }

        pendingAnimations.clear();
        animatingPlayers.clear();
    }
}
