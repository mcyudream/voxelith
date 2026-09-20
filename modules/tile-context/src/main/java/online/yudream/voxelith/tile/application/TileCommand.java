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

    /**
     * 增量重跑（推荐）：复用已发布图集 + **与全量渲染同款**的编码选项
     * （共享图集不内嵌 PNG、可选 {@code EXT_meshopt_compression}）。
     *
     * <p>放在 application 层是有架构原因的：编排域只允许引用本上下文的 application 层，
     * 不能直接碰 {@code EncodeOptions}（tile.domain）。跨上下文的调用方用这个工厂，
     * 既拿到与全量一致的编码，也不越层。</p>
     *
     * @param meshopt 是否启用 meshopt 熵编码（与全量渲染的 {@code render.meshopt} 同源）
     */
    public static TileCommand incremental(Map<ChunkPos, BakedChunkMeshData> meshes, Path outputDir,
                                          AtlasReuse reuseAtlas, boolean meshopt) {
        return new TileCommand(meshes, outputDir,
                EncodeOptions.sharedAtlas().withMeshopt(meshopt), reuseAtlas);
    }
}
