package online.yudream.voxelith.tile.infrastructure.bootstrap;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.PublishManifestUseCase;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.VertexColorTileExporter;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestPublisher;
import online.yudream.voxelith.tile.infrastructure.artifact.FileTileArtifactSink;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import online.yudream.voxelith.tile.infrastructure.image.CatalogTexturePixelSource;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;

/**
 * tile 上下文组合根：以资源目录为贴图来源装配 GenerateTilesUseCase。
 */
public final class TileContextBootstrap {

    private TileContextBootstrap() {
    }

    public static GenerateTilesUseCase openGenerator(ResolvedResourceCatalog catalog) {
        return new GenerateTilesUseCase(
                new CatalogTexturePixelSource(catalog),
                new PngImageCodec(),
                new GlbTileEncoder(),
                new FileTileArtifactSink());
    }

    public static PublishManifestUseCase openPublisher() {
        return new PublishManifestUseCase(new FileManifestPublisher());
    }

    /** 贴图平均色采样（LOD 柱状几何取色用）。 */
    public static TextureColorSampler openTextureColorSampler(ResolvedResourceCatalog catalog) {
        return new TextureColorSampler(new CatalogTexturePixelSource(catalog));
    }

    /** 无纹理纯色瓦片导出（LOD 瓦片编码落盘用）。 */
    public static VertexColorTileExporter openVertexColorTileExporter() {
        return new VertexColorTileExporter(new GlbTileEncoder(), new FileTileArtifactSink());
    }
}
