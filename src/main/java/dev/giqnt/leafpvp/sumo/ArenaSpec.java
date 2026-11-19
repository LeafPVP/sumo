package dev.giqnt.leafpvp.sumo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NullMarked;

/**
 * Where a map's arena is: players start on a circle of {@code spawnRadius} around {@code center} and are out below
 * {@code fallY}. Kept as {@code sumo.json} in the map folder, which the lobby and the game servers share.
 */
@NullMarked
record ArenaSpec(Vector center, double spawnRadius, double fallY) {
    static final String FILE = "sumo.json";
    static final double DEFAULT_SPAWN_RADIUS = 6;
    static final ArenaSpec DEFAULT = new ArenaSpec(new Vector(0.5, 64, 0.5), DEFAULT_SPAWN_RADIUS, 61);

    static double defaultFallY(final Vector center) {
        return center.getY() - 3;
    }

    /** Empty when the map has no file; throws when the file can't be understood. */
    static Optional<ArenaSpec> read(final Path mapFolder) throws IOException {
        final JsonObject json;
        try (final Reader reader = Files.newBufferedReader(mapFolder.resolve(FILE), StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (final NoSuchFileException e) {
            return Optional.empty();
        }
        final JsonArray center = json.getAsJsonArray("center");
        if (center == null || center.size() != 3) {
            throw new IllegalArgumentException("'center' is not a list of 3 numbers");
        }
        final Vector centerVector = new Vector(
                center.get(0).getAsDouble(),
                center.get(1).getAsDouble(),
                center.get(2).getAsDouble());
        return Optional.of(new ArenaSpec(
                centerVector,
                json.has("spawnRadius") ? json.get("spawnRadius").getAsDouble() : DEFAULT_SPAWN_RADIUS,
                json.has("fallY") ? json.get("fallY").getAsDouble() : defaultFallY(centerVector)));
    }

    /** Written through a temporary file: game servers read the file at the start of every round. */
    void write(final Path mapFolder) throws IOException {
        final JsonArray centerJson = new JsonArray();
        centerJson.add(center.getX());
        centerJson.add(center.getY());
        centerJson.add(center.getZ());
        final JsonObject json = new JsonObject();
        json.add("center", centerJson);
        json.addProperty("spawnRadius", spawnRadius);
        json.addProperty("fallY", fallY);
        final Path temp = mapFolder.resolve(FILE + ".tmp");
        try (final Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        }
        Files.move(temp, mapFolder.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
