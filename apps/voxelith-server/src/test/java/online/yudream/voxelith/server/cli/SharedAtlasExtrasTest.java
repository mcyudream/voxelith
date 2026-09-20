package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.infrastructure.artifact.FileTileArtifactSink;
import online.yudream.voxelith.tile.infrastructure.glb.GlbTileEncoder;
import online.yudream.voxelith.tile.infrastructure.image.CatalogTexturePixelSource;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多遍渲染的共享图集必须包含**运行时注册的贴图**（地图画 + 实体内置贴图）。
 *
 * <p>回归背景：共享图集的贴图清单来自采集产物（models.json.gz 的贴图表），
 * 而地图画与盔甲架贴图是运行时才注册的，不在那份清单里——于是多遍渲染
 * （`batch-chunks` 默认 2048，大图都会走这条路）里这些面会落进品红兜底格，
 * 单遍渲染却正常。本用例把「预打包清单 + 运行时贴图」这条组合钉住。</p>
 */
class SharedAtlasExtrasTest {

    /** 空资源目录：只验证运行时注册的贴图能进图集，不依赖任何真实贴图。 */
    private static ResolvedResourceCatalog emptyCatalog() {
        return new ResolvedResourceCatalog() {
            @Override
            public List<Identifier> blocks() {
                return List.of();
            }

            @Override
            public List<VariantGroup> selectVariantGroups(Identifier block, Map<String, String> state) {
                return List.of();
            }

            @Override
            public Optional<ModelData> model(Identifier modelId) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> texture(Identifier id) {
                return Optional.empty();
            }

            @Override
            public int biomeGrassColor(String biomeId) {
                return 0;
            }

            @Override
            public int biomeFoliageColor(String biomeId) {
                return 0;
            }

            @Override
            public int biomeWaterColor(String biomeId) {
                return 0;
            }
        };
    }

    @Test
    @DisplayName("地图画与盔甲架贴图能被共享图集打包（不再落品红兜底格）")
    void runtimeTexturesArePackableThroughTheChain(@TempDir Path workDir) {
        CatalogTexturePixelSource catalogSource = new CatalogTexturePixelSource(emptyCatalog());
        MapArtInjector mapArt = new MapArtInjector(catalogSource);
        EntityInjector entityInjector = new EntityInjector(mapArt);
        mapArt.register(42, new int[128 * 128]);

        GenerateTilesUseCase tiles = new GenerateTilesUseCase(
                entityInjector, new PngImageCodec(), new GlbTileEncoder(), new FileTileArtifactSink());

        // 模拟多遍渲染：预打包清单 = 采集产物贴图表（这里为空）+ 运行时贴图
        List<String> textureIds = new ArrayList<>();
        textureIds.addAll(mapArt.textureIds());
        textureIds.addAll(entityInjector.textureIds());
        AtlasReuse atlas = tiles.packSharedAtlas(workDir, textureIds);

        assertThat(atlas.layout().cellIndex().keySet())
                .as("地图与盔甲架贴图都要有独立单元格")
                .contains("voxelith:map/42", MapArtInjector.EMPTY_FRAME_TEXTURE,
                        EntityInjector.ARMOR_STAND_TEXTURE);
        // 第 0 格是品红兜底，运行时贴图不能落在它上面
        assertThat(atlas.layout().cellIndex().get(EntityInjector.ARMOR_STAND_TEXTURE))
                .isNotZero();
        assertThat(atlas.layout().cellIndex().get("voxelith:map/42")).isNotZero();
    }
}
