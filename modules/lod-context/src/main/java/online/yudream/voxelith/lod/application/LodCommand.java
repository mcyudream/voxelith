package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @param meshes         bake 链路产出的区块网格（与 tile 链路共享同一份内存产物）
 * @param outputDir      瓦片产物目录（LOD 写入 tiles/lod/{level}/{x}/{z}.glb）
 * @param maxLevel       最高 LOD 层级；0 = 自动（聚合到全图 ≤ 2×2 瓦片为止）
 * @param store          全图高度场仓储；null = 不读写（全量一次性生成）
 * @param replaceRegions 增量时先清空这些 region 的柱再 merge；空 = 用本次采样整场覆盖
 */
public record LodCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, int maxLevel,
                         HeightfieldStore store, List<RegionPos> replaceRegions) {

    public LodCommand {
        replaceRegions = replaceRegions == null ? List.of() : List.copyOf(replaceRegions);
    }

    public LodCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, int maxLevel) {
        this(meshes, outputDir, maxLevel, null, List.of());
    }
}
