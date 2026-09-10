package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;

import java.nio.file.Path;
import java.util.List;

/**
 * @param tiles        生成的瓦片摘要（含 sha1 与包围盒，供 manifest 链路复用）
 * @param atlasFile    图集 PNG 落盘路径
 * @param reportFile   报告落盘路径
 * @param textureCount 入集贴图数（含兜底格）
 * @param atlasSize    图集边长（像素）
 */
public record TileOutcome(List<TileSummary> tiles, Path atlasFile, Path reportFile,
                          int textureCount, int atlasSize) {

    public record TileSummary(TilePos pos, int quads, int vertices, int bytes, String sha1,
                              float[] min, float[] max) {
    }
}
