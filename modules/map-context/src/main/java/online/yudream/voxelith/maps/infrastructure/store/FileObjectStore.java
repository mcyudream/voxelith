package online.yudream.voxelith.maps.infrastructure.store;

import online.yudream.voxelith.maps.domain.ObjectStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地文件系统对象存储：{root}/{key}，与现有 publish-dir 布局 1:1。
 */
public final class FileObjectStore implements ObjectStore {

    private final Path root;

    public FileObjectStore(Path root) {
        this.root = root;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("写对象失败: " + file, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读对象失败: " + file, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("删对象失败: " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path dir = prefix.isEmpty() ? root : root.resolve(prefix);
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Files.isDirectory(dir) ? dir : dir.getParent())) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String key = root.relativize(file).toString().replace('\\', '/');
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出对象失败: " + prefix, e);
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private Path resolve(String key) {
        Path rootAbs = root.toAbsolutePath().normalize();
        Path file = rootAbs.resolve(key).normalize();
        if (!file.startsWith(rootAbs)) {
            throw new IllegalArgumentException("对象键越界: " + key);
        }
        return file;
    }
}
