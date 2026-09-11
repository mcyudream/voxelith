package online.yudream.voxelith.server.config;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.lod.application.GenerateLodPyramidUseCase;
import online.yudream.voxelith.lod.infrastructure.heightfield.FileHeightfieldStore;
import online.yudream.voxelith.orchestration.application.IncrementalUpdateUseCase;
import online.yudream.voxelith.orchestration.domain.IncrementalRenderPort;
import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.orchestration.infrastructure.incremental.RegionIncrementalRenderAdapter;
import online.yudream.voxelith.orchestration.infrastructure.incremental.TileManifestInvalidateAdapter;
import online.yudream.voxelith.orchestration.infrastructure.watch.WatchServiceRegionWatch;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
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

    @Bean(destroyMethod = "close")
    public WorldBlockAccess incrementalWorld(
            @Value("${yudream.voxelith.incremental.world-dir}") String worldDir,
            @Value("${yudream.voxelith.incremental.dimension:minecraft:overworld}") String dimension) {
        return WorldContextBootstrap.openBlockAccess(Path.of(worldDir), dimension);
    }

    @Bean(destroyMethod = "close")
    public ResolvedResourceCatalog incrementalCatalog(
            @Value("${yudream.voxelith.incremental.pack-dir:}") String packDir) {
        if (packDir == null || packDir.isBlank()) {
            throw new IllegalStateException(
                    "incremental.enabled=true 需要 yudream.voxelith.incremental.pack-dir");
        }
        return ResourceContextBootstrap.openCatalog(List.of(Path.of(packDir)));
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
            @Value("${yudream.voxelith.incremental.map-id:demo}") String mapId) {
        BakeChunksUseCase bake = new BakeChunksUseCase(
                incrementalCatalog, incrementalWorld, new FileBakeArtifactSink());
        GenerateTilesUseCase tiles = TileContextBootstrap.openGenerator(incrementalCatalog);
        GenerateLodPyramidUseCase lod = new GenerateLodPyramidUseCase(
                TileContextBootstrap.openTextureColorSampler(incrementalCatalog),
                TileContextBootstrap.openVertexColorTileExporter(),
                TileContextBootstrap.openImageCodec());
        Path mapDir = Path.of(publishDir).resolve(mapId);
        return new RegionIncrementalRenderAdapter(
                incrementalWorld, bake, tiles, lod, publishedAtlas,
                new FileHeightfieldStore(mapDir.resolve("heightfield.bin")),
                mapDir);
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
            @Value("${yudream.voxelith.incremental.dimension:minecraft:overworld}") String dimension) {
        Path world = Path.of(worldDir);
        Path regionDir = "minecraft:overworld".equals(dimension)
                ? world.resolve("region")
                : "minecraft:the_nether".equals(dimension)
                ? world.resolve("DIM-1").resolve("region")
                : world.resolve("DIM1").resolve("region");
        return new WatchServiceRegionWatch(regionDir);
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
