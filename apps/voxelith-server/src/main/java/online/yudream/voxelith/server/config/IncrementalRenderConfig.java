package online.yudream.voxelith.server.config;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.bake.infrastructure.prebaked.NdjsonPrebakedQuadSource;
import online.yudream.voxelith.lod.application.GenerateLodPyramidUseCase;
import online.yudream.voxelith.lod.infrastructure.heightfield.FileHeightfieldStore;
import online.yudream.voxelith.maps.domain.ObjectStore;
import online.yudream.voxelith.orchestration.application.IncrementalUpdateUseCase;
import online.yudream.voxelith.orchestration.domain.IncrementalRenderPort;
import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.orchestration.infrastructure.incremental.RegionIncrementalRenderAdapter;
import online.yudream.voxelith.orchestration.infrastructure.incremental.TileManifestInvalidateAdapter;
import online.yudream.voxelith.orchestration.infrastructure.watch.WatchServiceRegionWatch;
import online.yudream.voxelith.orchestration.infrastructure.watch.PollingRegionWatch;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.infrastructure.bootstrap.ResourceContextBootstrap;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.InvalidateManifestUseCase;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
import online.yudream.voxelith.tile.infrastructure.artifact.FilePublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.bootstrap.TileContextBootstrap;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * region 增量更新组合根：WatchService + bake→tile→lod 适配器 + 清单局部失效。
 * 默认关闭（{@code yudream.voxelith.incremental.enabled=false}）；开启时需要
 * 世界目录、资源包路径与已发布清单（图集复用 atlas-layout.json）。
 */
@Configuration
@ConditionalOnProperty(prefix = "yudream.voxelith.incremental", name = "enabled", havingValue = "true")
public class IncrementalRenderConfig {

    private static final Logger log = LoggerFactory.getLogger(IncrementalRenderConfig.class);

    @Bean(destroyMethod = "close")
    public WorldBlockAccess incrementalWorld(
            @Value("${yudream.voxelith.incremental.world-dir}") String worldDir,
            @Value("${yudream.voxelith.incremental.dimension:minecraft:overworld}") String dimension) {
        return WorldContextBootstrap.openBlockAccess(Path.of(worldDir), dimension);
    }

    /**
     * bake/tile 共用的资源目录。mod jar 叠在原版包之上（优先级更高）：mod 的 blockstate /
     * model / texture 必须都能解析，否则 mod 方块在 bake 侧查不到模型（无几何），
     * 贴图也会落成品红兜底格。
     */
    @Bean(destroyMethod = "close")
    public ResolvedResourceCatalog incrementalCatalog(
            @Value("${yudream.voxelith.incremental.pack-dir:}") String packDir,
            @Value("${yudream.voxelith.incremental.mod-jars:}") String modJars) {
        if (packDir == null || packDir.isBlank()) {
            throw new IllegalStateException(
                    "incremental.enabled=true 需要 yudream.voxelith.incremental.pack-dir");
        }
        List<Path> packs = new ArrayList<>();
        packs.add(Path.of(packDir));
        for (Path modJar : splitPaths(modJars)) {
            if (!Files.isRegularFile(modJar)) {
                throw new IllegalStateException("mod jar 不存在: " + modJar.toAbsolutePath());
            }
            packs.add(modJar);
        }
        return ResourceContextBootstrap.openCatalog(packs);
    }

    @Bean
    public PublishedAtlas publishedAtlas(
            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        return new FilePublishedAtlas(Path.of(publishDir));
    }

    @Bean
    public IncrementalRenderPort incrementalRenderPort(
            WorldBlockAccess incrementalWorld,
            ResolvedResourceCatalog incrementalCatalog,
            PublishedAtlas publishedAtlas,
            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir,
            @Value("${yudream.voxelith.work-dir:./work}") String workDir,
            @Value("${yudream.voxelith.incremental.models-file:}") String modelsFile,
            @Value("${yudream.voxelith.render.meshopt:true}") boolean meshopt,
            @Value("${yudream.voxelith.incremental.map-id:demo}") String mapId) {
        BakeChunksUseCase bake = new BakeChunksUseCase(
                incrementalCatalog, incrementalWorld, new FileBakeArtifactSink(),
                resolvePrebaked(modelsFile, workDir));
        GenerateTilesUseCase tiles = TileContextBootstrap.openGenerator(incrementalCatalog);
        GenerateLodPyramidUseCase lod = new GenerateLodPyramidUseCase(
                TileContextBootstrap.openTextureColorSampler(incrementalCatalog),
                // LOD 与 hires 都跟全量渲染用同一套压缩开关
                TileContextBootstrap.openVertexColorTileExporter(meshopt),
                TileContextBootstrap.openImageCodec());
        Path mapDir = Path.of(publishDir).resolve(mapId);
        return new RegionIncrementalRenderAdapter(
                incrementalWorld, bake, tiles, lod, publishedAtlas,
                // 增量扩图集：新方块/mod 方块的贴图追加进已发布图集（老瓦片 UV 不动）
                TileContextBootstrap.openAtlasExpander(Path.of(publishDir), incrementalCatalog),
                new FileHeightfieldStore(mapDir.resolve("heightfield.bin")),
                mapDir,
                // 增量 hires 瓦片与全量渲染同款：共享图集（不内嵌 PNG）+ 可选 meshopt
                meshopt);
    }

    /**
     * runtime 采集产物 models.json.gz 的解析（Phase 5 → bake 消费）：显式配置优先，
     * 缺省探测 {@code work-dir/models.json.gz}。未命中时返回 null 让 bake 退回静态模型解析，
     * 而不是让应用启动失败——管线可能还没跑过采集。
     */
    private static PrebakedQuadSource resolvePrebaked(String modelsFile, String workDir) {
        Path path = modelsFile != null && !modelsFile.isBlank()
                ? Path.of(modelsFile)
                : Path.of(workDir).resolve("models.json.gz");
        if (!Files.isRegularFile(path)) {
            log.warn("未找到 runtime 采集产物 {}，bake 退回静态模型解析（mod 方块可能缺几何或贴图）；"
                    + "先执行 `gradle :apps:voxelith-server:harvestModels` 生成", path.toAbsolutePath());
            return null;
        }
        PrebakedQuadSource source = NdjsonPrebakedQuadSource.load(path);
        log.info("bake 使用 runtime 采集模型集: {}", path.toAbsolutePath());
        return source;
    }

    /** 逗号/分号分隔的路径列表（跳过空白项）。 */
    private static List<Path> splitPaths(String raw) {
        List<Path> paths = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return paths;
        }
        for (String piece : raw.split("[,;]")) {
            if (!piece.isBlank()) {
                paths.add(Path.of(piece.trim()));
            }
        }
        return paths;
    }

    @Bean
    public ManifestInvalidatePort manifestInvalidatePort(
            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        return new TileManifestInvalidateAdapter(
                new InvalidateManifestUseCase(new FileManifestStore(Path.of(publishDir))));
    }

    @Bean
    public RegionWatchPort regionWatchPort(
            @Value("${yudream.voxelith.incremental.world-dir}") String worldDir,
            @Value("${yudream.voxelith.incremental.dimension:minecraft:overworld}") String dimension,
            @Value("${yudream.voxelith.incremental.watch-mode:local}") String watchMode,
            @Value("${yudream.voxelith.incremental.object-prefix:}") String objectPrefix,
            @Value("${yudream.voxelith.incremental.poll-seconds:15}") long pollSeconds,
            ObjectStore objectStore) {
        Path world = Path.of(worldDir);
        // 维度路径统一由 world 上下文给出（下界 DIM-1、末地 DIM1）
        Path regionDir = WorldContextBootstrap.dimensionDir(world, dimension).resolve("region");
        if ("object-store".equalsIgnoreCase(watchMode)) {
            // 存档放在 S3/MinIO/R2 上：没有 inotify，只能「列出 + 比对 ETag」轮询，
            // 变更的对象顺手镜像到本地 region 目录（增量渲染读的是本地 Anvil 文件）
            String prefix = objectPrefix == null || objectPrefix.isBlank()
                    ? regionPrefix(dimension) : objectPrefix;
            return new PollingRegionWatch(new ObjectStoreRegionSource(objectStore), prefix, regionDir,
                    Duration.ofSeconds(Math.max(1, pollSeconds)));
        }
        return new WatchServiceRegionWatch(regionDir);
    }

    /** 对象存储里 region 的默认前缀：与存档目录结构一致（DIM-1/DIM1 是下界/末地）。 */
    private static String regionPrefix(String dimension) {
        String subPath = WorldContextBootstrap.dimensionSubPath(dimension);
        return subPath.isEmpty() ? "region/" : subPath + "/region/";
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService incrementalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voxelith-incremental");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(destroyMethod = "close")
    public IncrementalUpdateUseCase incrementalUpdateUseCase(
            RegionWatchPort regionWatchPort,
            IncrementalRenderPort incrementalRenderPort,
            ManifestInvalidatePort manifestInvalidatePort,
            ScheduledExecutorService incrementalScheduler,
            @Value("${yudream.voxelith.incremental.map-id:demo}") String mapId) {
        IncrementalUpdateUseCase useCase = new IncrementalUpdateUseCase(
                regionWatchPort, incrementalRenderPort, manifestInvalidatePort,
                incrementalScheduler, IncrementalUpdateUseCase.DEFAULT_DEBOUNCE, mapId);
        useCase.start();
        return useCase;
    }
}
