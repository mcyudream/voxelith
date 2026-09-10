package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.tile.application.ManifestStore;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文件系统清单仓储：{publishRoot}/{mapId}/manifest.json，.tmp + 原子替换。
 */
public final class FileManifestStore implements ManifestStore {

    private final Path publishRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FileManifestStore(Path publishRoot) {
        this.publishRoot = publishRoot;
    }

    @Override
    public Optional<MapManifest> load(String mapId) {
        Path file = publishRoot.resolve(mapId).resolve("manifest.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromJson(JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()));
        } catch (IOException e) {
            throw new UncheckedIOException("读取清单失败: " + file, e);
        }
    }

    @Override
    public void save(String mapId, MapManifest manifest) {
        Path mapDir = publishRoot.resolve(mapId);
        try {
            Files.createDirectories(mapDir);
            Path tmp = mapDir.resolve("manifest.json.tmp");
            Files.writeString(tmp, gson.toJson(toJson(manifest)), StandardCharsets.UTF_8);
            Files.move(tmp, mapDir.resolve("manifest.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写清单失败: " + mapDir, e);
        }
    }

    static JsonObject toJson(MapManifest manifest) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", manifest.formatVersion());
        root.addProperty("mapId", manifest.mapId());
        root.addProperty("name", manifest.name());
        root.addProperty("version", manifest.version());
        root.addProperty("generatedAt", manifest.generatedAt());

        JsonObject settings = new JsonObject();
        settings.addProperty("hiresTileSize", manifest.settings().hiresTileSize());
        settings.addProperty("lodCount", manifest.settings().lodCount());
        root.add("settings", settings);

        root.add("boundsMin", floats(manifest.boundsMin()));
        root.add("boundsMax", floats(manifest.boundsMax()));

        JsonObject atlas = new JsonObject();
        atlas.addProperty("url", manifest.atlas().url());
        atlas.addProperty("size", manifest.atlas().size());
        atlas.addProperty("textureCount", manifest.atlas().textureCount());
        root.add("atlas", atlas);

        JsonArray tiles = new JsonArray();
        for (MapManifest.TileEntry tile : manifest.tiles()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("level", tile.level());
            entry.addProperty("x", tile.x());
            entry.addProperty("z", tile.z());
            entry.addProperty("url", tile.url());
            entry.addProperty("sha1", tile.sha1());
            entry.addProperty("bytes", tile.bytes());
            entry.addProperty("quads", tile.quads());
            entry.add("min", floats(tile.min()));
            entry.add("max", floats(tile.max()));
            tiles.add(entry);
        }
        root.add("tiles", tiles);
        return root;
    }

    static MapManifest fromJson(JsonObject root) {
        JsonObject settings = root.getAsJsonObject("settings");
        JsonObject atlas = root.getAsJsonObject("atlas");
        List<MapManifest.TileEntry> tiles = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("tiles")) {
            JsonObject t = el.getAsJsonObject();
            tiles.add(new MapManifest.TileEntry(
                    t.get("level").getAsInt(), t.get("x").getAsInt(), t.get("z").getAsInt(),
                    t.get("url").getAsString(), t.get("sha1").getAsString(),
                    t.get("bytes").getAsInt(), t.get("quads").getAsInt(),
                    floats(t.getAsJsonArray("min")), floats(t.getAsJsonArray("max"))));
        }
        return new MapManifest(
                root.get("formatVersion").getAsInt(),
                root.get("mapId").getAsString(),
                root.get("name").getAsString(),
                root.get("version").getAsString(),
                root.get("generatedAt").getAsString(),
                new MapManifest.Settings(
                        settings.get("hiresTileSize").getAsInt(),
                        settings.get("lodCount").getAsInt()),
                floats(root.getAsJsonArray("boundsMin")),
                floats(root.getAsJsonArray("boundsMax")),
                new MapManifest.AtlasRef(
                        atlas.get("url").getAsString(),
                        atlas.get("size").getAsInt(),
                        atlas.get("textureCount").getAsInt()),
                List.copyOf(tiles));
    }

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static float[] floats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }
}
