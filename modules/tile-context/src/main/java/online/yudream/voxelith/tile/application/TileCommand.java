package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param meshes     bake 链路产出的区块网格（application 层契约）
 * @param outputDir  瓦片产物目录（tiles/hires/{x}/{z}.glb + atlas.png + tile-report.json）
 * @param encode     编码选项；默认未压缩 float32，{@link EncodeOptions#quantized()} 启用 KHR_mesh_quantization
 * @param reuseAtlas 增量重跑时复用已发布图集（避免重打包打乱 UV）；null = 现场打包
 */
public record TileCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, EncodeOptions encode,
                          AtlasReuse reuseAtlas) {

    public TileCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir) {
        this(meshes, outputDir, EncodeOptions.uncompressed(), null);
    }

    public TileCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, EncodeOptions encode) {
        this(meshes, outputDir, encode, null);
    }

    /** 增量重跑：默认未压缩，复用已发布图集。 */
    public TileCommand(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir, AtlasReuse reuseAtlas) {
        this(meshes, outputDir, EncodeOptions.uncompressed(), reuseAtlas);
    }
}
