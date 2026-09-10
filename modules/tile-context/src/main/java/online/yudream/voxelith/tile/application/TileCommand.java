package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param meshes    bake 链路产出的区块网格（application 层契约）
 * @param outputDir 瓦片产物目录（tiles/hires/{x}/{z}.glb + atlas.png + tile-report.json）
 */
public record TileCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir) {
}
