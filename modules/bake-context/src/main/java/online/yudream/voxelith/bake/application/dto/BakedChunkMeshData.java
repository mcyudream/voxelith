package online.yudream.voxelith.bake.application.dto;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.util.List;
import java.util.Map;

/**
 * 跨上下文传递的区块烘焙网格。
 */
public record BakedChunkMeshData(ChunkPos pos, List<BakedQuadData> quads,
                                 Map<String, Integer> missing, int blocksBaked) {
}
