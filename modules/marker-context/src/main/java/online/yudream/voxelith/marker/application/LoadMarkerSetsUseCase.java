package online.yudream.voxelith.marker.application;

import online.yudream.voxelith.marker.domain.MarkerRepository;
import online.yudream.voxelith.marker.domain.MarkerSet;

import java.util.Comparator;
import java.util.List;

/**
 * 读一张地图的标注：按 sorting 降序返回（前端按顺序叠加渲染）。
 */
public class LoadMarkerSetsUseCase {

    private final MarkerRepository repository;

    public LoadMarkerSetsUseCase(MarkerRepository repository) {
        this.repository = repository;
    }

    /** @return 标注集列表（无标注文件时为空列表） */
    public List<MarkerSet> load(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("mapId 不能为空");
        }
        return repository.load(mapId)
                .map(sets -> sets.stream()
                        .sorted(Comparator.comparingInt(MarkerSet::sorting).reversed())
                        .toList())
                .orElseGet(List::of);
    }
}
