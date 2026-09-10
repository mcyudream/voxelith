package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;

import java.nio.file.Path;
import java.util.List;

/**
 * tile 链路产物落盘端口（实现位于 infrastructure）。
 */
public interface TileArtifactSink {

    /** 写瓦片 glb，返回落盘路径。 */
    Path writeTile(Path outputDir, TilePos pos, byte[] glb);

    /** 写图集 PNG，返回落盘路径。 */
    Path writeAtlas(Path outputDir, byte[] png);

    /**
     * 写 atlas-layout.json（增量重跑复用 UV 所需）。
     * 默认实现只返回路径，不落盘；文件系统实现写入 JSON。
     */
    default Path writeAtlasLayout(Path outputDir, AtlasLayout layout) {
        return outputDir.resolve("atlas-layout.json");
    }

    /** 写 tile-report.json，返回落盘路径。 */
    Path writeReport(Path outputDir, List<TileOutcome.TileSummary> tiles, int textureCount, int atlasSize);
}
