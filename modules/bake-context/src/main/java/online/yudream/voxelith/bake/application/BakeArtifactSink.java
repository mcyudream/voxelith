package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.bake.domain.mesh.BakedQuad;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * bake 链路产物落盘端口。
 */
public interface BakeArtifactSink {

    /** 写单区块几何样本（JSON）。返回写入路径。 */
    Path writeChunkSample(Path outputDir, ChunkPos pos, List<BakedQuad> quads);

    /** 写烘焙报告。 */
    Path writeReport(Path outputDir, int chunksBaked, int blocksBaked, int quadsBaked,
                     Map<String, Integer> missing, List<Path> sampleFiles);
}
