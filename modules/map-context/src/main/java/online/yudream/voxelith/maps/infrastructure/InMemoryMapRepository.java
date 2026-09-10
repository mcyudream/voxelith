package online.yudream.voxelith.maps.infrastructure;

import online.yudream.voxelith.maps.domain.GameMap;
import online.yudream.voxelith.maps.domain.MapRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存仓储（测试与临时装配用）。运行期地图事实来源为 {@link FileSystemMapRepository}。
 */
public class InMemoryMapRepository implements MapRepository {

    private final ConcurrentMap<String, GameMap> store = new ConcurrentHashMap<>();

    @Override
    public List<GameMap> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public Optional<GameMap> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void save(GameMap map) {
        store.put(map.id(), map);
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }
}
