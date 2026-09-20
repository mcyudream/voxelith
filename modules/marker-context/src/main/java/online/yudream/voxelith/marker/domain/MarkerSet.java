package online.yudream.voxelith.marker.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标注集：一组可整体显隐的标注（如「地标」「路线」「区域」）。
 *
 * @param id        标注集 id（地图内唯一）
 * @param label     展示名
 * @param toggleable 前端是否允许用户开关（false = 始终显示当前状态）
 * @param hiddenByDefault 初始是否隐藏
 * @param sorting   排序（越大越靠前，前端取降序）
 * @param markers   标注列表
 */
public record MarkerSet(String id, String label, boolean toggleable, boolean hiddenByDefault,
                        int sorting, List<Marker> markers) {

    public MarkerSet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("标注集 id 不能为空");
        }
        label = label == null || label.isBlank() ? id : label;
        markers = markers == null ? List.of() : List.copyOf(markers);
        Set<String> ids = new HashSet<>();
        for (Marker marker : markers) {
            if (marker.id() == null || marker.id().isBlank()) {
                throw new IllegalArgumentException("标注 id 不能为空（标注集 " + id + "）");
            }
            if (!ids.add(marker.id())) {
                throw new IllegalArgumentException("标注 id 重复: " + marker.id() + "（标注集 " + id + "）");
            }
            if (marker.minDistance() > marker.maxDistance()) {
                throw new IllegalArgumentException("minDistance 不能大于 maxDistance: " + marker.id());
            }
        }
    }

    public static MarkerSet of(String id, String label, List<Marker> markers) {
        return new MarkerSet(id, label, true, false, 0, markers);
    }
}
