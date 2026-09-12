package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * tilesDir 已是发布目录时只写清单（全量烘焙后同卷改名发布，避免再占一份磁盘）。
 */
public final class FileManifestPublisher implements ManifestPublisher {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public Path publish(String mapId, Path tilesDir, MapManifest manifest, Path publishRoot) {
        Path mapDir = publishRoot.resolve(mapId);
        try {
            Path source = tilesDir.toAbsolutePath().normalize();
            Path target = mapDir.toAbsolutePath().normalize();
            if (!source.equals(target)) {
                if (Files.isDirectory(target)) {
                    deleteTree(target);
                }
                Files.createDirectories(target);
                copyTree(source.resolve("tiles"), target.resolve("tiles"));
                Files.copy(source.resolve("atlas.png"), target.resolve("atlas.png"),
                        StandardCopyOption.REPLACE_EXISTING);
                copyIfPresent(source.resolve(AtlasLayoutFiles.FILE_NAME),
                        target.resolve(AtlasLayoutFiles.FILE_NAME));
                copyIfPresent(source.resolve("heightfield.bin"), target.resolve("heightfield.bin"));
            }

            Path manifestFile = mapDir.resolve("manifest.json");
            Path tmp = mapDir.resolve("manifest.json.tmp");
            Files.write(tmp, gson.toJson(FileManifestStore.toJson(manifest)).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, manifestFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return manifestFile;
        } catch (IOException e) {
            throw new UncheckedIOException("清单发布失败: " + mapDir, e);
        }
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

    private static void copyIfPresent(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from) && !from.equals(to)) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> deleteQuietly(p));
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
