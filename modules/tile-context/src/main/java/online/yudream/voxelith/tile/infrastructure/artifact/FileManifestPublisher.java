package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.tile.application.ManifestPublisher;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件系统清单发布：瓦片产物复制到 {publishRoot}/{mapId}/，manifest.json 先写 .tmp 再原子改名。
 */
public final class FileManifestPublisher implements ManifestPublisher {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public Path publish(String mapId, Path tilesDir, MapManifest manifest, Path publishRoot) {
        Path mapDir = publishRoot.resolve(mapId);
        try {
            if (Files.isDirectory(mapDir)) {
                try (Stream<Path> walk = Files.walk(mapDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> deleteQuietly(p));
                }
            }
            Files.createDirectories(mapDir);
            copyTree(tilesDir.resolve("tiles"), mapDir.resolve("tiles"));
            Files.copy(tilesDir.resolve("atlas.png"), mapDir.resolve("atlas.png"),
                    StandardCopyOption.REPLACE_EXISTING);

            Path manifestFile = mapDir.resolve("manifest.json");
            Path tmp = mapDir.resolve("manifest.json.tmp");
            Files.write(tmp, gson.toJson(toJson(manifest)).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, manifestFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return manifestFile;
        } catch (IOException e) {
            throw new UncheckedIOException("清单发布失败: " + mapDir, e);
        }
    }

    private JsonObject toJson(MapManifest manifest) {
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

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("清理旧发布目录失败: " + path, e);
        }
    }
}
