package online.yudream.voxelith.marker.application;

import online.yudream.voxelith.marker.domain.MarkerRepository;
import online.yudream.voxelith.marker.domain.MarkerSet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保存一张地图的标注（整表替换）。
 *
 * <p>为什么是整表替换而不是逐个增删：标注总量很小（几百个点），整表写回是原子的、
 * 前端不需要维护服务端状态，也不会出现「删了一半」的中间态。写入由仓储做
 * .tmp + 原子改名（见 FileMarkerRepository）。</p>
 */
public class SaveMarkerSetsUseCase {

    /** 单张地图的标注上限：再多就该换数据源（数据库 / 分片文件），不该塞进一个 JSON。 */
    public static final int MAX_MARKERS = 5000;

    private final MarkerRepository repository;

    public SaveMarkerSetsUseCase(MarkerRepository repository) {
        this.repository = repository;
    }

    /**
     * @param mapId 地图 id
     * @param sets  完整标注集列表（空列表 = 清空该地图的标注）
     * @return 实际写入的标注数
     * @throws IllegalArgumentException 参数非法（id 重复、坐标越界、超量等）
     */
    public int save(String mapId, List<MarkerSet> sets) {
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("mapId 不能为空");
        }
        if (mapId.contains("..") || mapId.contains("/") || mapId.contains("\\")) {
            throw new IllegalArgumentException("mapId 含有非法字符: " + mapId);
        }
        List<MarkerSet> validated = sets == null ? List.of() : List.copyOf(sets);
        Set<String> setIds = new HashSet<>();
        int total = 0;
        for (MarkerSet set : validated) {
            if (!setIds.add(set.id())) {
                throw new IllegalArgumentException("标注集 id 重复: " + set.id());
            }
            total += set.markers().size();
        }
        if (total > MAX_MARKERS) {
            throw new IllegalArgumentException("标注过多: " + total + " > " + MAX_MARKERS);
        }
        repository.save(mapId, validated);
        return total;
    }
}
