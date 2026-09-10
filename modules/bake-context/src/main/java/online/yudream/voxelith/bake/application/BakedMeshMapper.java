package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.bake.domain.mesh.BakedQuad;
import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 domain 网格结果转换为 application 层契约 DTO，供下游上下文（tile/lod）消费。
 */
public final class BakedMeshMapper {

    private BakedMeshMapper() {
    }

    public static BakedChunkMeshData toData(ChunkMeshResult result) {
        List<BakedQuadData> quads = result.quads().stream()
                .map(BakedMeshMapper::toData)
                .toList();
        return new BakedChunkMeshData(result.pos(), quads, result.missing(), result.blocksBaked());
    }

    public static BakedQuadData toData(BakedQuad quad) {
        return new BakedQuadData(quad.positions(), quad.uvs(), quad.normal(),
                quad.texture(), quad.tintIndex(), quad.shade(), quad.face(),
                quad.tintRgb(), quad.skyLight(), quad.blockLight(), quad.ao(),
                quad.translucent());
    }

    public static Map<ChunkPos, BakedChunkMeshData> toData(Map<ChunkPos, ChunkMeshResult> meshes) {
        Map<ChunkPos, BakedChunkMeshData> data = new LinkedHashMap<>();
        meshes.forEach((pos, result) -> data.put(pos, toData(result)));
        return data;
    }
}
