package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.manifest.MapManifest;

import java.nio.file.Path;

/**
 * 清单发布端口：把瓦片产物目录整体发布到发布根下的 {mapId}/ 并原子写 manifest.json。
 */
public interface ManifestPublisher {

    /**
     * @param mapId       地图 id（发布目录名）
     * @param tilesDir    tile 链路产物目录（含 tiles/、atlas.png、tile-report.json）
     * @param manifest    清单
     * @param publishRoot 发布根目录
     * @return 落盘的 manifest.json 路径
     */
    Path publish(String mapId, Path tilesDir, MapManifest manifest, Path publishRoot);
}
