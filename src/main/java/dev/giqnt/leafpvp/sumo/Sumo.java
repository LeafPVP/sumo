package dev.giqnt.leafpvp.sumo;

import dev.giqnt.leafpvp.duel.api.Minigame;
import dev.giqnt.leafpvp.duel.api.MinigameContext;
import dev.giqnt.leafpvp.duel.api.MinigameMatch;
import dev.giqnt.leafpvp.duel.api.PartyRule;
import dev.giqnt.leafpvp.duel.api.TeamRule;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@NullMarked
public class Sumo implements Minigame, Listener {
    private final Map<World, SumoMatch> matches = new ConcurrentHashMap<>();
    private final Set<String> warnedMaps = new HashSet<>();

    @Nullable private Logger logger;

    @Override
    public String id() {
        return "sumo";
    }

    @Override
    public String name() {
        return "<green><bold>Sumo</bold></green>";
    }

    @Override
    public ItemStack icon() {
        return new ItemStack(Material.SLIME_BALL);
    }

    void setLogger(final Logger logger) {
        this.logger = logger;
    }

    // Each map folder is read every round so changes made on the lobby apply without a restart.
    private ArenaSpec readSpec(final MinigameContext context, final Logger logger) {
        try {
            final Optional<ArenaSpec> spec = ArenaSpec.read(context.mapFolder());
            if (spec.isPresent()) return spec.get();
            if (warnedMaps.add(context.mapName())) {
                logger.warn("Map '{}' has no {}, using the default arena", context.mapName(), ArenaSpec.FILE);
            }
        } catch (final IOException | RuntimeException e) {
            logger.warn("Could not read {} of map '{}', using the default arena", ArenaSpec.FILE, context.mapName(), e);
        }
        return ArenaSpec.DEFAULT;
    }

    @Override
    public TeamRule teamRule() {
        return TeamRule.range(2, TeamRule.MAX_TEAMS).withFfa().defaultTeams(0);
    }

    @Override
    public int queueSize() {
        return 2;
    }

    @Override
    public PartyRule partyRule() {
        return PartyRule.of(PartyRule.min(2));
    }

    @Override
    public boolean supportsRounds() {
        return true;
    }

    @Override
    public MinigameMatch start(final MinigameContext context) {
        if (logger == null) throw new IllegalStateException("Sumo is not enabled");
        final SumoMatch match = new SumoMatch(context, readSpec(context, logger));
        matches.put(context.world(), match);
        return match;
    }

    // kept past the round's end so players stay protected while the result shows
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(final WorldUnloadEvent e) {
        matches.remove(e.getWorld());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        final SumoMatch match = matches.get(victim.getWorld());
        if (match == null) return;
        if (e instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player attacker
                && match.canHit(attacker, victim)) {
            e.setDamage(0);
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent e) {
        final SumoMatch match = matches.get(e.getTo().getWorld());
        if (match == null) return;
        match.onMove(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(final FoodLevelChangeEvent e) {
        if (matches.containsKey(e.getEntity().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent e) {
        if (matches.containsKey(e.getPlayer().getWorld())) {
            e.setCancelled(true);
        }
    }
}
