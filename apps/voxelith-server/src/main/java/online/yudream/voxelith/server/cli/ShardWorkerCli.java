package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.bake.infrastructure.prebaked.NdjsonPrebakedQuadSource;
import online.yudream.voxelith.lod.application.GenerateLodPyramidUseCase;
import online.yudream.voxelith.lod.infrastructure.heightfield.FileHeightfieldStore;
import online.yudream.voxelith.orchestration.application.ShardWorkerPool;
import online.yudream.voxelith.orchestration.domain.IncrementalJob;
import online.yudream.voxelith.orchestration.domain.IncrementalPatch;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.infrastructure.incremental.RegionIncrementalRenderAdapter;
import online.yudream.voxelith.orchestration.infrastructure.incremental.TileManifestInvalidateAdapter;
import online.yudream.voxelith.orchestration.infrastructure.shard.FileShardQueue;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.infrastructure.bootstrap.ResourceContextBootstrap;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
import online.yudream.voxelith.tile.infrastructure.artifact.FilePublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.bootstrap.TileContextBootstrap;
import online.yudream.voxelith.tile.application.InvalidateManifestUseCase;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布式分片 worker 入口（Phase 7「分布式分片作业队列」的仓库内入口）。
 *
 * <p>多个 worker 进程（可以在不同机器上，只要看得见同一个队列目录）从
 * {@code {queueDir}} 里抢占 region 分片，各自跑「读世界 → bake → tile → (lod) →
 * 局部失效清单」，把产物直接写进已发布地图目录。</p>
 *
 * <p>与 {@code renderMap} 的分工：{@code renderMap} 是全量管线（单机一次跑完），
 * worker 是**增量/补片**的执行端——把一件大事拆成按 region 的分片，谁空谁领。
 * 调度侧（例如服务端或 {@code RunPipelineUseCase} 的队列模式）只负责入队与收尾。</p>
 *
 * <p><b>产物路径基准</b>：worker 把瓦片直接写进 {@code publishDir/{mapId}}，回填给队列的
 * 也是相对该目录的路径。调度侧如果用自己的 runDir 校验产物存在性，会误判「产物丢失」——
 * 跨进程部署时请让 {@code RunPipelineUseCase} 的 {@code artifactRoot} 指向同一个地图目录。</p>
 *
 * <p>用法：</p>
 * <pre>
 * gradle :apps:voxelith-server:shardWorker -PworldDir=&lt;存档&gt; -PmapId=&lt;地图&gt; \
 *     -Ppacks=&lt;原版 client.jar[,mod.jar...]&gt; -PqueueDir=&lt;共享队列目录&gt; \
 *     [-Pthreads=4] [--once]
 * </pre>
 *
 * <p>退出码：0 = 队列已空并退出；1 = 有分片失败或等待超时；2 = 参数错误。</p>
 */
public final class ShardWorkerCli {

    private ShardWorkerCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            System.exit(run(options));
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
        }
    }

    static int run(Options options) {
        Path mapDir = options.publishDir.resolve(options.mapId);
        if (!Files.isDirectory(mapDir)) {
            System.err.println("已发布地图目录不存在: " + mapDir + "（先跑一次 renderMap 全量发布）");
            return 1;
        }

        ResolvedResourceCatalog catalog = ResourceContextBootstrap.openCatalog(options.packs);
        PublishedAtlas publishedAtlas = new FilePublishedAtlas(options.publishDir);
        InvalidateManifestUseCase invalidateUseCase =
                new InvalidateManifestUseCase(new FileManifestStore(options.publishDir));
        TileManifestInvalidateAdapter invalidate = new TileManifestInvalidateAdapter(invalidateUseCase);
        GenerateTilesUseCase tiles = TileContextBootstrap.openGenerator(catalog);
        GenerateLodPyramidUseCase lod = new GenerateLodPyramidUseCase(
                TileContextBootstrap.openTextureColorSampler(catalog),
                TileContextBootstrap.openVertexColorTileExporter(options.meshopt()),
                TileContextBootstrap.openImageCodec());
        PrebakedQuadSource prebaked = resolvePrebaked(options);

        FileShardQueue queue = new FileShardQueue(options.queueDir);
        ShardWorkerPool pool = new ShardWorkerPool(queue,
                Duration.ofMinutes(options.leaseMinutes), Duration.ofMinutes(options.idleMinutes));

        System.out.printf("worker %s：队列 %s，地图 %s，线程 %d%n",
                options.workerId, options.queueDir.toAbsolutePath(), options.mapId, options.threads);

        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(
                options.worldDir, options.dimension)) {
            RegionIncrementalRenderAdapter render = new RegionIncrementalRenderAdapter(
                    world,
                    new BakeChunksUseCase(catalog, world, new FileBakeArtifactSink(), prebaked),
                    tiles, lod, publishedAtlas,
                    TileContextBootstrap.openAtlasExpander(options.publishDir, catalog),
                    new FileHeightfieldStore(mapDir.resolve("heightfield.bin")),
                    mapDir);

            List<online.yudream.voxelith.orchestration.domain.ShardJob> jobs = pool.drainStage(
                    PipelineStage.BAKE, options.workerId, options.threads, (stage, shard) -> {
                        RegionPos region = parseShard(shard);
                        IncrementalPatch patch = render.rerender(
                                new IncrementalJob(options.mapId, List.of(region),
                                        System.currentTimeMillis()));
                        invalidate.invalidate(options.mapId, patch);
                        return artifactsOf(patch);
                    });
            long done = jobs.stream().filter(job -> job.state()
                    == online.yudream.voxelith.orchestration.domain.ShardJobState.DONE).count();
            long failed = jobs.size() - done;
            System.out.printf("队列已空：完成 %d，失败 %d%n", done, failed);
            return failed == 0 ? 0 : 1;
        } catch (Exception e) {
            System.err.println("worker 异常退出: " + e);
            return 1;
        }
    }

    /** 分片产物 = 本次被更新的瓦片 url（它们已经写进地图目录，检查点据此判断「产物健在」）。 */
    private static List<String> artifactsOf(IncrementalPatch patch) {
        Map<String, String> urls = new LinkedHashMap<>();
        patch.sha1ByUrl().forEach((url, sha1) -> {
            if (sha1 != null && !sha1.isEmpty()) {
                urls.put(url, sha1);
            }
        });
        for (IncrementalPatch.NewTile tile : patch.inserts()) {
            urls.put(tile.url(), tile.sha1());
        }
        return List.copyOf(urls.keySet());
    }

    static RegionPos parseShard(String shard) {
        // 分片键是 region 名去掉扩展名（r.X.Z）
        return RegionPos.parseFileName(shard + ".mca")
                .orElseThrow(() -> new IllegalArgumentException("非法分片键: " + shard));
    }

    private static PrebakedQuadSource resolvePrebaked(Options options) {
        Path path = options.modelsFile != null
                ? options.modelsFile
                : options.workDir.resolve("models.json.gz");
        return Files.isRegularFile(path) ? NdjsonPrebakedQuadSource.load(path) : null;
    }

    /** worker 启动参数。 */
    record Options(Path worldDir, String dimension, String mapId, List<Path> packs, Path workDir,
                   Path publishDir, Path modelsFile, Path queueDir, String workerId, int threads,
                   int leaseMinutes, int idleMinutes, boolean meshopt) {

        static final String USAGE = """
                shardWorker —— 分布式分片 worker（抢占队列里的 region 分片并增量重渲染）

                用法: gradle :apps:voxelith-server:shardWorker -PworldDir=<存档> -PmapId=<地图> \\
                        -Ppacks=<jar,jar> -PqueueDir=<共享队列目录> [-Pthreads=4]

                选项:
                  worldDir      必填：存档根目录（含 region/）
                  mapId         必填：已发布地图 id（worker 把产物直接写进它的目录）
                  packs         必填：资源包，逗号分隔（低 → 高优先级，第一个是原版 jar）
                  queueDir      必填：分片队列目录（多机共享同一目录即可协作）
                  publishDir    发布根目录（默认 ./data/maps）
                  workDir       中间产物目录（默认 ./work；用于找 models.json.gz）
                  modelsFile    指定 models.json.gz；留空 = workDir/models.json.gz
                  dimension     维度（默认 minecraft:overworld）
                  workerId      worker 标识（默认 主机名#进程号）
                  threads       本地并发线程数（默认 1；每台机器各自配置）
                  leaseMinutes  分片租约（默认 30；worker 崩溃后到期可被回收）
                  idleMinutes   无新完成分片的容忍时间（默认 10）
                  noMeshopt     true = 关闭 EXT_meshopt_compression（默认开）
                """;

        static Options parse(String[] args) {
            String worldDir = null;
            String mapId = null;
            List<String> packs = new ArrayList<>();
            String publishDir = "./data/maps";
            String workDir = "./work";
            String modelsFile = null;
            String queueDir = null;
            String dimension = "minecraft:overworld";
            String workerId = defaultWorkerId();
            int threads = 1;
            int leaseMinutes = 30;
            int idleMinutes = 10;
            boolean meshopt = true;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--world-dir", "-worldDir" -> worldDir = args[++i];
                    case "--map-id", "-mapId" -> mapId = args[++i];
                    case "--packs", "-packs" -> packs.add(args[++i]);
                    case "--publish-dir", "-publishDir" -> publishDir = args[++i];
                    case "--work-dir", "-workDir" -> workDir = args[++i];
                    case "--models-file", "-modelsFile" -> modelsFile = args[++i];
                    case "--queue-dir", "-queueDir" -> queueDir = args[++i];
                    case "--dimension", "-dimension" -> dimension = args[++i];
                    case "--worker-id", "-workerId" -> workerId = args[++i];
                    case "--threads", "-threads" -> threads = Integer.parseInt(args[++i]);
                    case "--lease-minutes", "-leaseMinutes" ->
                            leaseMinutes = Integer.parseInt(args[++i]);
                    case "--idle-minutes", "-idleMinutes" ->
                            idleMinutes = Integer.parseInt(args[++i]);
                    case "--no-meshopt", "-noMeshopt" -> meshopt = false;
                    default -> throw new IllegalArgumentException("未知参数: " + args[i]);
                }
            }
            if (worldDir == null || mapId == null || queueDir == null) {
                throw new IllegalArgumentException("缺少 worldDir / mapId / queueDir");
            }
            if (packs.isEmpty()) {
                throw new IllegalArgumentException("缺少 packs（至少需要原版 client jar）");
            }
            List<Path> packPaths = new ArrayList<>();
            for (String item : packs) {
                for (String piece : item.split("[,;]")) {
                    if (!piece.isBlank()) {
                        packPaths.add(Path.of(piece.trim()));
                    }
                }
            }
            return new Options(Path.of(worldDir), dimension, mapId, List.copyOf(packPaths),
                    Path.of(workDir), Path.of(publishDir),
                    modelsFile == null || modelsFile.isBlank() ? null : Path.of(modelsFile),
                    Path.of(queueDir), workerId, Math.max(1, threads),
                    Math.max(1, leaseMinutes), Math.max(1, idleMinutes), meshopt);
        }

        private static String defaultWorkerId() {
            try {
                return java.net.InetAddress.getLocalHost().getHostName()
                        + "#" + ProcessHandle.current().pid();
            } catch (Exception e) {
                return "worker#" + ProcessHandle.current().pid();
            }
        }
    }
}
