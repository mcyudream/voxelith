package online.yudream.voxelith.orchestration.domain;

import java.util.List;
import java.util.Map;

/**
 * 增量清单补丁：编排域自有 DTO，不引用 tile.domain.MapManifest。
 *
 * @param sha1ByUrl 被更新瓦片 url → 新 sha1（空串 = 删除）
 * @param inserts   尚未出现在清单中的新瓦片
 */
public record IncrementalPatch(Map<String, String> sha1ByUrl, List<NewTile> inserts) {

    public IncrementalPatch {
        sha1ByUrl = Map.copyOf(sha1ByUrl);
        inserts = List.copyOf(inserts);
    }

    public static IncrementalPatch empty() {
        return new IncrementalPatch(Map.of(), List.of());
    }

    public record NewTile(String url, int level, int x, int z, String sha1, int bytes, int quads,
                          float[] min, float[] max) {
    }
}
