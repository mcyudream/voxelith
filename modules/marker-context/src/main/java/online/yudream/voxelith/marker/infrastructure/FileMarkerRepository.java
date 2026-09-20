package online.yudream.voxelith.marker.infrastructure;

import online.yudream.voxelith.marker.domain.MarkerRepository;
import online.yudream.voxelith.marker.domain.MarkerSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * 标注仓储落盘：{publishRoot}/{mapId}/markers.json（与瓦片、清单同一目录，静态服务直接可读）。
 *
 * <p>写入走 .tmp + 原子改名：标注文件会被前端持续轮询/重载，半写状态会被 zod 判为非法，
 * 让用户看到「标注突然消失」。</p>
 */
@Repository
public final class FileMarkerRepository implements MarkerRepository {

    public static final String FILE_NAME = "markers.json";

    private final Path publishRoot;
    private final JsonMarkerCodec codec = new JsonMarkerCodec();

    /** Spring 装配用（发布目录与瓦片/清单同一个）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public FileMarkerRepository(@Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        this(Path.of(publishDir));
    }

    public FileMarkerRepository(Path publishRoot) {
        this.publishRoot = publishRoot;
    }

    @Override
    public Optional<List<MarkerSet>> load(String mapId) {
        Path file = fileOf(mapId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.read(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("读标注失败: " + file, e);
        }
    }

    @Override
    public void save(String mapId, List<MarkerSet> sets) {
        Path file = fileOf(mapId);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, codec.write(mapId, sets), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写标注失败: " + file, e);
        }
    }

    /** 对象键越界防护：mapId 直接来自 URL。 */
    private Path fileOf(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("mapId 不能为空");
        }
        Path root = publishRoot.toAbsolutePath().normalize();
        Path file = root.resolve(mapId).resolve(FILE_NAME).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("mapId 越界: " + mapId);
        }
        return file;
    }
}
