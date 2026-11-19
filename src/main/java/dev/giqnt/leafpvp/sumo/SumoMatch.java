package dev.giqnt.leafpvp.sumo;

import dev.giqnt.leafpvp.duel.api.Countdown;
import dev.giqnt.leafpvp.duel.api.MinigameContext;
import dev.giqnt.leafpvp.duel.api.MinigameMatch;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class SumoMatch implements MinigameMatch {
    private static final Title WAITING_TITLE = Title.title(
            Component.empty(),
            Component.text("다른 플레이어들을 기다리는 중입니다...", NamedTextColor.GRAY),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(1)));

    private enum Status {
        WAITING,
        COUNTDOWN,
        PLAYING,
        ENDED
    }

    private final MinigameContext context;
    private final ArenaSpec spec;
    private final Location center;
    private final Map<UUID, Location> spawns = new HashMap<>();
    private final Set<UUID> alive = new HashSet<>();
    private @Nullable Countdown countdown;
    private Status status = Status.WAITING;

    SumoMatch(final MinigameContext context, final ArenaSpec spec) {
        this.context = context;
        this.spec = spec;
        this.center = spec.center().toLocation(context.world());
        final Set<UUID> left = context.leftPlayers();
        final List<UUID> players = context.teams().stream()
                .flatMap(List::stream)
                .filter(uuid -> !left.contains(uuid))
                .toList();
        for (int i = 0; i < players.size(); i++) {
            final double angle = -Math.PI / 2 + 2 * Math.PI * i / players.size();
            final Location spawn =
                    center.clone().add(Math.cos(angle) * spec.spawnRadius(), 0, Math.sin(angle) * spec.spawnRadius());
            spawn.setDirection(center.toVector().subtract(spawn.toVector()));
            spawns.put(players.get(i), spawn);
        }
    }

    @Override
    public void onJoin(final Player player) {
        final Location spawn = spawns.get(player.getUniqueId());
        if (status != Status.WAITING || spawn == null) {
            spectate(player);
            return;
        }
        reset(player);
        player.teleport(spawn);
        player.showTitle(WAITING_TITLE);
    }

    @Override
    public void onReady(final Set<UUID> present) {
        if (status != Status.WAITING) return;
        alive.addAll(present);
        status = Status.COUNTDOWN;
        countdown = context.startCountdown(() -> status = Status.PLAYING);
    }

    @Override
    public void onQuit(final Player player) {
        if ((status == Status.COUNTDOWN || status == Status.PLAYING) && alive.contains(player.getUniqueId())) {
            eliminate(player);
        }
    }

    @Override
    public void onSpectatorJoin(final Player player) {
        spectate(player);
    }

    @Override
    public void onEnd() {
        status = Status.ENDED;
    }

    boolean canHit(final Player attacker, final Player victim) {
        return status == Status.PLAYING
                && alive.contains(attacker.getUniqueId())
                && alive.contains(victim.getUniqueId())
                && !Objects.equals(teamOf(attacker.getUniqueId()), teamOf(victim.getUniqueId()));
    }

    void onMove(final PlayerMoveEvent e) {
        final Player player = e.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Location spawn = spawns.get(uuid);
        if (spawn == null || (status != Status.WAITING && !alive.contains(uuid))) return;
        final Location to = e.getTo();
        switch (status) {
            case WAITING, COUNTDOWN -> {
                if (to.getY() < spec.fallY()) {
                    player.teleport(spawn);
                } else if (to.getX() != e.getFrom().getX()
                        || to.getZ() != e.getFrom().getZ()) {
                    e.setTo(e.getFrom().setDirection(to.getDirection()));
                }
            }
            case PLAYING -> {
                if (to.getY() < spec.fallY()) {
                    spectate(player);
                    eliminate(player);
                }
            }
            case ENDED -> {}
        }
    }

    private void eliminate(final Player player) {
        alive.remove(player.getUniqueId());
        audience().sendMessage(mini("<gray><player> 탈락!", Placeholder.unparsed("player", player.getName())));
        final Set<List<UUID>> teams = aliveTeams();
        if (teams.size() <= 1) {
            finish(teams.stream().findFirst().orElse(null));
        }
    }

    private void finish(@Nullable final List<UUID> winners) {
        if (status == Status.ENDED) return;
        status = Status.ENDED;
        if (countdown != null) {
            countdown.cancel();
        }
        final int winnerTeam = winners == null ? -1 : context.teams().indexOf(winners);
        context.end(winnerTeam < 0 ? null : winnerTeam);
    }

    private void reset(final Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.clearActivePotionEffects();
        final var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(maxHealth.getAttribute().getDefaultValue());
            player.setHealth(maxHealth.getValue());
        }
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    private void spectate(final Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(center.clone().add(0, 5, 0));
    }

    private @Nullable List<UUID> teamOf(final UUID uuid) {
        return context.teams().stream()
                .filter(team -> team.contains(uuid))
                .findFirst()
                .orElse(null);
    }

    private Set<List<UUID>> aliveTeams() {
        return context.teams().stream()
                .filter(team -> team.stream().anyMatch(alive::contains))
                .collect(Collectors.toSet());
    }

    private Audience audience() {
        return Audience.audience(context.world().getPlayers());
    }

    private static Component mini(final String text, final TagResolver... resolvers) {
        return MiniMessage.miniMessage().deserialize(text, resolvers);
    }
}
