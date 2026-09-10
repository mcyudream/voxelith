package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param meshes    bake 链路产出的区块网格（与 tile 链路共享同一份内存产物）
 * @param outputDir 瓦片产物目录（LOD 写入 tiles/lod/{level}/{x}/{z}.glb）
 * @param maxLevel  最高 LOD 层级；0 = 自动（聚合到全图 ≤ 2×2 瓦片为止）
 */
public record LodCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, int maxLevel) {
}
