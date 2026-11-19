package dev.giqnt.leafpvp.sumo;

import dev.giqnt.leafpvp.duel.api.Addon;
import dev.giqnt.leafpvp.duel.api.AddonContext;
import dev.giqnt.leafpvp.duel.api.Minigame;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class SumoAddon implements Addon {
    private final Sumo sumo = new Sumo();
    private @Nullable ArenaEditor editor;

    @Override
    public List<Minigame> minigames() {
        return List.of(sumo);
    }

    @Override
    public void enable(final AddonContext context) {
        sumo.setLogger(context.logger());
        // matches only run on game servers; the lobby reads the name, icon and rules and sets arenas up
        if (context.role() == AddonContext.ServerRole.GAME) {
            context.plugin().getServer().getPluginManager().registerEvents(sumo, context.plugin());
        } else {
            final ArenaEditor created = new ArenaEditor(context);
            context.plugin().getServer().getPluginManager().registerEvents(created, context.plugin());
            editor = created;
        }
    }

    @Override
    public void registerCommands(final Commands commands) {
        final ArenaEditor current = editor;
        if (current != null) current.register(commands);
    }
}
