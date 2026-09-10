package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.tile.application.TileOutcome;

import java.util.List;

/**
 * @param tiles  生成的 LOD 瓦片摘要（level ≥ 1，供 manifest 链路合并发布）
 * @param levels 实际生成的 LOD 层级数（manifest settings.lodCount = levels + 1）
 */
public record LodOutcome(List<TileOutcome.TileSummary> tiles, int levels) {
}
