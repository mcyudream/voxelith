package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * bake 链路结果。inMemory 几何供编排层直接传递 tile 链路，样本与报告已落盘。
 */
public record BakeOutcome(int chunksBaked, int blocksBaked, int quadsBaked,
                          Map<String, Integer> missing,
                          List<Path> sampleFiles,
                          Map<ChunkPos, ChunkMeshResult> meshes) {

    public Optional<ChunkMeshResult> mesh(ChunkPos pos) {
        return Optional.ofNullable(meshes.get(pos));
    }
}
