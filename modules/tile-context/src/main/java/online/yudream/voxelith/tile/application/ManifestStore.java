package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.manifest.MapManifest;

import java.util.Optional;

/**
 * 清单仓储端口：读/写已发布的 manifest.json（局部失效与全量发布共用）。
 */
public interface ManifestStore {

    Optional<MapManifest> load(String mapId);

    void save(String mapId, MapManifest manifest);
}
