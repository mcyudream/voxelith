package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.domain.mesh.BiomeTintResolver;
import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder;
import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * bake 链路用例：逐区块网格化（模型→quad、cullface 邻居遮挡剔除、逐顶点光照+AO 烘焙），
 * 样本几何与覆盖率报告落盘，全部网格内存中返回给编排层传递 tile 链路。
 * 区块级并行（烘焙为纯 CPU，WorldBlockAccess 契约线程安全）；
 * 结果按区块坐标排序落盘/返回，保证产物确定性与并发度无关。
 */
public class BakeChunksUseCase {

    private final ResolvedResourceCatalog catalog;
    private final WorldBlockAccess world;
    private final BakeArtifactSink sink;
    /** runtime 采集的真实 BakedModel quad 源，null = 纯静态模型解析。 */
    private final PrebakedQuadSource prebaked;

    public BakeChunksUseCase(ResolvedResourceCatalog catalog, WorldBlockAccess world, BakeArtifactSink sink) {
        this(catalog, world, sink, null);
    }

    public BakeChunksUseCase(ResolvedResourceCatalog catalog, WorldBlockAccess world,
                             BakeArtifactSink sink, PrebakedQuadSource prebaked) {
        this.catalog = catalog;
        this.world = world;
        this.sink = sink;
        this.prebaked = prebaked;
    }

    public BakeOutcome bake(BakeCommand command) {
        ChunkMeshBuilder builder = new ChunkMeshBuilder(catalog, world,
                new BiomeTintResolver(catalog, world), prebaked);
        int total = command.chunks().size();
        AtomicInteger done = new AtomicInteger();
        long startedAt = System.currentTimeMillis();

        Map<ChunkPos, ChunkMeshResult> meshes = command.chunks().parallelStream()
                .map(chunk -> {
                    ChunkMeshResult result = builder.buildChunk(chunk);
                    int finished = done.incrementAndGet();
                    if (finished % 500 == 0 || finished == total) {
                        long elapsed = System.currentTimeMillis() - startedAt;
                        System.out.printf("[bake] %d/%d chunks, %.1fs elapsed%n",
                                finished, total, elapsed / 1000.0);
                    }
                    return Map.entry(chunk, result);
                })
                .collect(() -> new TreeMap<ChunkPos, ChunkMeshResult>(
                                Comparator.comparing(ChunkPos::x).thenComparing(ChunkPos::z)),
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        TreeMap::putAll);

        Map<String, Integer> missing = new LinkedHashMap<>();
        List<Path> sampleFiles = new ArrayList<>();
        int blocksBaked = 0;
        int quadsBaked = 0;
        for (ChunkMeshResult result : meshes.values()) {
            blocksBaked += result.blocksBaked();
            quadsBaked += result.quads().size();
            result.missing().forEach((id, count) -> missing.merge(id, count, Integer::sum));
            if (sampleFiles.size() < command.sampleChunks()) {
                sampleFiles.add(sink.writeChunkSample(command.outputDir(), result.pos(), result.quads()));
            }
        }

        sink.writeReport(command.outputDir(), command.chunks().size(), blocksBaked, quadsBaked,
                missing, sampleFiles);

        return new BakeOutcome(command.chunks().size(), blocksBaked, quadsBaked,
                missing, sampleFiles, meshes);
    }
}
