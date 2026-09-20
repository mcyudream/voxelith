package online.yudream.voxelith.tile.infrastructure.bootstrap;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.EnsureAtlasCapacityUseCase;
import online.yudream.voxelith.tile.application.PublishManifestUseCase;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.VertexColorTileExporter;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestPublisher;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
import online.yudream.voxelith.tile.infrastructure.artifact.FilePublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.artifact.FileTileArtifactSink;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import online.yudream.voxelith.tile.infrastructure.image.CatalogTexturePixelSource;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;

import java.nio.file.Path;

/**
 * tile 上下文组合根：以资源目录为贴图来源装配 GenerateTilesUseCase。
 */
public final class TileContextBootstrap {

    private TileContextBootstrap() {
    }

    public static GenerateTilesUseCase openGenerator(ResolvedResourceCatalog catalog) {
        return openGenerator(catalog, new CatalogTexturePixelSource(catalog));
    }

    /**
     * 自定义贴图像素来源的生成器：地图画等「非资源包」贴图需要包一层
     * （地图颜色来自存档 data/map_*.dat，不在资源包里）。
     */
    public static GenerateTilesUseCase openGenerator(ResolvedResourceCatalog catalog,
                                                     TexturePixelSource pixelSource) {
        return new GenerateTilesUseCase(
                pixelSource,
                new PngImageCodec(),
                new GlbTileEncoder(),
                new FileTileArtifactSink());
    }

    public static PublishManifestUseCase openPublisher() {
        return new PublishManifestUseCase(new FileManifestPublisher());
    }

    /** 贴图平均色 / UV 区域采样（LOD 柱状几何与航拍色图取色用）。 */
    public static TextureColorSampler openTextureColorSampler(ResolvedResourceCatalog catalog) {
        return new TextureColorSampler(new CatalogTexturePixelSource(catalog));
    }

    /** 无纹理纯色瓦片导出（LOD 瓦片编码落盘用）。 */
    public static VertexColorTileExporter openVertexColorTileExporter() {
        return new VertexColorTileExporter(new GlbTileEncoder(), new FileTileArtifactSink());
    }

    /** LOD 瓦片导出：{@code meshopt=true} 时同样走 EXT_meshopt_compression 熵编码。 */
    public static VertexColorTileExporter openVertexColorTileExporter(boolean meshopt) {
        return new VertexColorTileExporter(new GlbTileEncoder(), new FileTileArtifactSink(),
                EncodeOptions.uncompressed().withMeshopt(meshopt));
    }

    /** PNG 编码器（LOD 航拍色图嵌入 glb）。 */
    public static PngImageCodec openImageCodec() {
        return new PngImageCodec();
    }

    /** 已发布图集读写（增量重跑复用 UV）。 */
    public static PublishedAtlas openPublishedAtlas(Path publishRoot) {
        return new FilePublishedAtlas(publishRoot);
    }

    /**
     * 增量扩图集用例：已发布图集里缺的贴图（新方块/mod 方块）追加进去，
     * 老单元格与已发布瓦片的 UV 不动。
     */
    public static EnsureAtlasCapacityUseCase openAtlasExpander(Path publishRoot,
                                                                ResolvedResourceCatalog catalog) {
        return new EnsureAtlasCapacityUseCase(
                openPublishedAtlas(publishRoot),
                new FileManifestStore(publishRoot),
                new CatalogTexturePixelSource(catalog),
                new PngImageCodec());
    }
}
