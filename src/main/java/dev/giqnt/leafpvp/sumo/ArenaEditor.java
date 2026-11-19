package dev.giqnt.leafpvp.sumo;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.giqnt.leafpvp.duel.api.AddonContext;
import dev.giqnt.leafpvp.duel.api.GameWorld;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * {@code /sumo edit <map>} on the lobby: opens a copy of the map and lets an admin stand where the arena's center,
 * its spawn circle and its fall height are, then writes them to the map folder.
 */
@NullMarked
final class ArenaEditor implements Listener {
    // the permission duel lets open maps that are not enabled yet, which is when arenas are set up
    private static final String PERMISSION = "pvp.duel.admin";
    private static final String MAP_ID_PREFIX = "minigames/sumo/";

    private enum Tool {
        CENTER(0, Material.BEACON, "중심 지정", "서 있는 블록 칸의 가운데와 발 높이를 경기장 중심으로 정합니다."),
        RADIUS(1, Material.LEAD, "스폰 반지름 지정", "중심에서 지금 서 있는 곳까지의 수평 거리를 스폰 반지름으로 정합니다."),
        FALL(2, Material.FEATHER, "낙하 높이 지정", "지금 발 높이보다 아래로 내려가면 탈락하게 정합니다."),
        SAVE(7, Material.WRITABLE_BOOK, "저장", "지금 값을 맵에 저장합니다."),
        EXIT(8, Material.BARRIER, "나가기", "저장하지 않은 값은 버리고 로비로 돌아갑니다.");

        private final int slot;
        private final Material material;
        private final String title;
        private final String description;

        Tool(final int slot, final Material material, final String title, final String description) {
            this.slot = slot;
            this.material = material;
            this.title = title;
            this.description = description;
        }
    }

    private static final class Edit {
        private final GameWorld world;
        private final boolean couldFly;
        private Vector center;
        private double spawnRadius;
        // null until it is set: it then follows the center, as it does for a file without it
        private @Nullable Double fallY;

        private Edit(final GameWorld world, final boolean couldFly, final @Nullable ArenaSpec saved) {
            this.world = world;
            this.couldFly = couldFly;
            this.center = (saved == null ? ArenaSpec.DEFAULT : saved).center().clone();
            this.spawnRadius = saved == null ? ArenaSpec.DEFAULT_SPAWN_RADIUS : saved.spawnRadius();
            this.fallY = saved == null ? null : saved.fallY();
        }

        private ArenaSpec spec() {
            final Double fall = fallY;
            return new ArenaSpec(center.clone(), spawnRadius, fall != null ? fall : ArenaSpec.defaultFallY(center));
        }
    }

    private final AddonContext context;
    private final NamespacedKey toolKey;
    private final Map<UUID, Edit> edits = new HashMap<>();

    ArenaEditor(final AddonContext context) {
        this.context = context;
        this.toolKey = new NamespacedKey(context.plugin(), "sumo_edit_tool");
    }

    void register(final Commands commands) {
        commands.register(
                Commands.literal("sumo")
                        .requires(source -> source.getSender().hasPermission(PERMISSION))
                        .then(Commands.literal("edit")
                                .then(Commands.argument("map", StringArgumentType.greedyString())
                                        .executes(command -> {
                                            if (command.getSource().getExecutor() instanceof Player player) {
                                                open(player, StringArgumentType.getString(command, "map"));
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .build(),
                "Set up the arena of a sumo map");
    }

    private void open(final Player player, final String mapName) {
        try {
            context.openWorld(player, MAP_ID_PREFIX + mapName, () -> closed(player))
                    .thenAccept(world -> {
                        // a future keeps what is thrown here to itself
                        try {
                            begin(player, world);
                        } catch (final RuntimeException e) {
                            context.logger().error("Could not start editing map '{}'", mapName, e);
                            world.close();
                        }
                    });
        } catch (final IllegalArgumentException e) {
            player.sendRichMessage("<red>맵 이름이 올바르지 않습니다.");
        }
    }

    private void begin(final Player player, final GameWorld world) {
        ArenaSpec saved = null;
        try {
            saved = ArenaSpec.read(folder(world)).orElse(null);
        } catch (final IOException | RuntimeException e) {
            context.logger().warn("Could not read {} of map '{}'", ArenaSpec.FILE, world.name(), e);
            player.sendRichMessage("<red>저장된 값을 읽지 못해 기본값에서 시작합니다.");
        }
        final Edit edit = new Edit(world, player.getAllowFlight(), saved);
        edits.put(player.getUniqueId(), edit);
        for (final Tool tool : Tool.values()) {
            player.getInventory().setItem(tool.slot, item(tool));
        }
        land(player, edit);
        player.sendRichMessage(saved == null ? "<yellow>저장된 값이 없는 맵입니다. 기본값에서 시작합니다." : "<yellow>저장된 값을 불러왔습니다.");
        show(player, edit);
    }

    // The copy has no floor of its own, and the spot a map is entered at need not have one either.
    private void land(final Player player, final Edit edit) {
        player.setAllowFlight(true);
        player.setFlying(true);
        final Location center = edit.center.toLocation(edit.world.world());
        center.setDirection(player.getLocation().getDirection());
        player.teleport(center);
    }

    private void closed(final Player player) {
        final Edit edit = edits.remove(player.getUniqueId());
        if (edit == null || edit.couldFly) return;
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private ItemStack item(final Tool tool) {
        final ItemStack item = new ItemStack(tool.material);
        item.editMeta(meta -> {
            meta.displayName(
                    Component.text(tool.title, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(tool.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name());
        });
        return item;
    }

    private @Nullable Tool tool(final @Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        final String name = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (name == null) return null;
        try {
            return Tool.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static Path folder(final GameWorld world) {
        return Objects.requireNonNull(world.folder());
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) return;
        final Player player = event.getPlayer();
        final Edit edit = edits.get(player.getUniqueId());
        final Tool tool = tool(event.getItem());
        if (edit == null || tool == null) return;
        event.setCancelled(true);
        final Location at = player.getLocation();
        switch (tool) {
            case CENTER -> {
                edit.center = new Vector(at.getBlockX() + 0.5, round(at.getY(), 100), at.getBlockZ() + 0.5);
                show(player, edit);
            }
            case RADIUS -> {
                final double radius =
                        round(Math.hypot(at.getX() - edit.center.getX(), at.getZ() - edit.center.getZ()), 2);
                if (radius <= 0) {
                    player.sendRichMessage("<red>중심에서 떨어진 곳에 서서 사용하세요.");
                    return;
                }
                edit.spawnRadius = radius;
                show(player, edit);
            }
            case FALL -> {
                edit.fallY = round(at.getY(), 100);
                show(player, edit);
            }
            case SAVE -> save(player, edit);
            case EXIT -> edit.world.close();
        }
    }

    private void save(final Player player, final Edit edit) {
        try {
            edit.spec().write(folder(edit.world));
            player.sendRichMessage("<green>저장했습니다. 다음 판부터 적용됩니다.");
        } catch (final IOException | RuntimeException e) {
            context.logger().error("Could not write {} of map '{}'", ArenaSpec.FILE, edit.world.name(), e);
            player.sendRichMessage("<red>저장하지 못했습니다.");
        }
    }

    private static void show(final Player player, final Edit edit) {
        final ArenaSpec spec = edit.spec();
        player.sendRichMessage(
                "<gray>중심 <white>" + spec.center().getX() + ", " + spec.center().getY() + ", "
                        + spec.center().getZ() + "</white> · 스폰 반지름 <white>" + spec.spawnRadius()
                        + "</white> · 낙하 높이 <white>" + spec.fallY() + "</white>"
                        + (edit.fallY == null ? " (중심보다 3칸 아래)" : ""));
    }

    private static double round(final double value, final int perUnit) {
        return (double) Math.round(value * perUnit) / perUnit;
    }

    // dying in the session respawns in it, without the flight that kept the admin over the void
    @EventHandler
    public void onPostRespawn(final PlayerPostRespawnEvent event) {
        final Player player = event.getPlayer();
        final Edit edit = edits.get(player.getUniqueId());
        if (edit != null && player.getWorld().equals(edit.world.world())) land(player, edit);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        if (edits.containsKey(event.getPlayer().getUniqueId())
                && tool(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }
}
