package online.yudream.voxelith.orchestration.domain;

import java.util.Map;

/**
 * 清单局部失效端口：把指定瓦片条目的 sha1 替换为新值（空串 = 删除该条目），
 * 再按全部条目重算 contentVersion，原子写回 manifest.json。
 * 未被点名的条目保持原 sha1，前端缓存只对变化瓦片失效。
 */
public interface ManifestInvalidatePort {

    /**
     * @param mapId     地图 id
     * @param sha1ByUrl 被更新瓦片 url → 新 sha1（空串表示删除）
     */
    void invalidate(String mapId, Map<String, String> sha1ByUrl);
}
