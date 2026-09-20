package online.yudream.voxelith.marker.domain;

import java.util.List;
import java.util.Optional;

/**
 * 标注仓储端口：按地图 id 读写整个标注集列表。
 *
 * <p>实现落在 infrastructure（发布目录里的 {@code markers.json}）；
 * 没有标注时返回 {@link Optional#empty()} 而不是空列表——「没配过」与「配成了空」在
 * 前端要区分（前者可以给默认提示，后者是用户真的清空了）。</p>
 */
public interface MarkerRepository {

    Optional<List<MarkerSet>> load(String mapId);

    void save(String mapId, List<MarkerSet> sets);
}
