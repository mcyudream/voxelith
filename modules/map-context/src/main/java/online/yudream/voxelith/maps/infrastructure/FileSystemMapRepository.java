package online.yudream.voxelith.maps.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.voxelith.maps.domain.GameMap;
import online.yudream.voxelith.maps.domain.MapRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

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
 * 发布目录扫描仓储：以 {@code ${publish-dir}/{mapId}/manifest.json} 为地图事实来源。
 *
 * <p>渲染管线（tile-context 的 ManifestPublisher）落盘即发布，无需进程内注册；
 * 每次查询重新扫描目录，新发布的地图无需重启即可被 /api/maps 列出。
 * 二期接入 SQLite 后由其实现替换，接口不变。
 */
@Repository
public class FileSystemMapRepository implements MapRepository {

    private final Path publishDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileSystemMapRepository(@Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        this.publishDir = Path.of(publishDir);
    }

    @Override
    public List<GameMap> findAll() {
        if (!Files.isDirectory(publishDir)) {
            return List.of();
        }
        List<GameMap> maps = new ArrayList<>();
        try (Stream<Path> children = Files.list(publishDir)) {
            for (Path dir : children.filter(Files::isDirectory).toList()) {
                readMap(dir).ifPresent(maps::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描地图发布目录失败: " + publishDir, e);
        }
        maps.sort(Comparator.comparing(GameMap::id));
        return maps;
    }

    @Override
    public Optional<GameMap> findById(String id) {
        return readMap(publishDir.resolve(id));
    }

    @Override
    public void save(GameMap map) {
        throw new UnsupportedOperationException("发布目录仓储为只读；地图由渲染管线落盘发布");
    }

    @Override
    public void delete(String id) {
        throw new UnsupportedOperationException("发布目录仓储为只读");
    }

    private Optional<GameMap> readMap(Path dir) {
        Path manifestPath = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try {
            JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
            String id = manifest.path("mapId").asText(dir.getFileName().toString());
            String name = manifest.path("name").asText(id);
            GameMap map = new GameMap(id, name, dir.toAbsolutePath().toString(),
                    "minecraft:overworld", null, null);
            map.markReady();
            return Optional.of(map);
        } catch (IOException e) {
            // 损坏的清单不阻断其它地图列出
            return Optional.empty();
        }
    }
}
