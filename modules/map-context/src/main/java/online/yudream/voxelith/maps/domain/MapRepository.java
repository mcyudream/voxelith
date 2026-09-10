package online.yudream.voxelith.maps.domain;

import java.util.List;
import java.util.Optional;

/**
 * 地图仓储端口，由 infrastructure 层实现。
 */
public interface MapRepository {

    List<GameMap> findAll();

    Optional<GameMap> findById(String id);

    void save(GameMap map);

    void delete(String id);
}
