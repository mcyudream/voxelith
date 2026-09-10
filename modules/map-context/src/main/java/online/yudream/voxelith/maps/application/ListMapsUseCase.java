package online.yudream.voxelith.maps.application;

import online.yudream.voxelith.maps.domain.GameMap;
import online.yudream.voxelith.maps.domain.MapRepository;

import java.util.List;

/**
 * 用例：列出全部地图。
 */
public class ListMapsUseCase {

    private final MapRepository mapRepository;

    public ListMapsUseCase(MapRepository mapRepository) {
        this.mapRepository = mapRepository;
    }

    public List<MapSummary> execute() {
        return mapRepository.findAll().stream()
                .map(MapSummary::of)
                .toList();
    }

    public GameMap requireById(String id) {
        return mapRepository.findById(id)
                .orElseThrow(() -> new MapNotFoundException(id));
    }
}
