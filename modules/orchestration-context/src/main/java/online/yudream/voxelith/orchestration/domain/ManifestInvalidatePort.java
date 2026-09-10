package online.yudream.voxelith.orchestration.domain;

/**
 * 清单局部失效端口：按 url 替换/删除指定瓦片，并可插入尚未出现的新条目，
 * 再按全部条目重算 contentVersion，原子写回 manifest.json。
 */
public interface ManifestInvalidatePort {

    void invalidate(String mapId, IncrementalPatch patch);
}
