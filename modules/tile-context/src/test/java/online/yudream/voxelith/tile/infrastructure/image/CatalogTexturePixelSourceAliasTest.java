package online.yudream.voxelith.tile.infrastructure.image;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨版本贴图改名兜底：资源包里只有新名（{@code short_grass}）时，引用旧名
 * （{@code grass}）的面不该落进品红兜底格，而应自动用同义贴图顶上，并留下记录。
 *
 * <p>这是「队友拉代码后草变紫」那类问题的兼容性兜底：版本对齐仍是正解，
 * 但版本有偏差时画面不该碎。</p>
 */
class CatalogTexturePixelSourceAliasTest {

    /** 只有一张贴图的假资源目录（贴图 id 可配置）。 */
    private static ResolvedResourceCatalog onlyTexture(String textureId, byte[] png) {
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
                return id.toString().equals(textureId) ? Optional.of(png) : Optional.empty();
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

    private static byte[] greenPng() {
        return new PngImageCodec().encodePng(16, 16, solid(16, 0xFF00FF00));
    }

    private static int[] solid(int size, int argb) {
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, argb);
        return pixels;
    }

    @Test
    @DisplayName("旧名找不到时用新名顶替，并记录「谁顶替了谁」")
    void fallsBackToRenamedTexture() {
        // 包里只有新名 short_grass；渲染引用的是旧名 grass（1.20.3 之前的模型）
        CatalogTexturePixelSource source = new CatalogTexturePixelSource(
                onlyTexture("minecraft:block/short_grass", greenPng()));

        Optional<AtlasTexture> loaded = source.load(Identifier.parse("minecraft:block/grass"));
        assertThat(loaded).isPresent();
        // 图集里仍按**原名**记账（UV/格子不变），只是像素来自同义贴图
        assertThat(loaded.get().id().toString()).isEqualTo("minecraft:block/grass");
        assertThat(loaded.get().argb()[0]).isEqualTo(0xFF00FF00);

        assertThat(source.substitutions()).hasSize(1);
        assertThat(source.substitutions().getFirst().getKey()).isEqualTo("minecraft:block/grass");
        assertThat(source.substitutions().getFirst().getValue())
                .isEqualTo("minecraft:block/short_grass");
    }

    @Test
    @DisplayName("原名命中时不产生顶替记录")
    void noSubstitutionWhenDirectHit() {
        CatalogTexturePixelSource source = new CatalogTexturePixelSource(
                onlyTexture("minecraft:block/grass", greenPng()));
        assertThat(source.load(Identifier.parse("minecraft:block/grass"))).isPresent();
        assertThat(source.substitutions()).isEmpty();
    }

    @Test
    @DisplayName("别名也没有时返回空（由调用方落品红兜底格并报告缺贴图）")
    void emptyWhenNeitherNameExists() {
        CatalogTexturePixelSource source = new CatalogTexturePixelSource(
                onlyTexture("minecraft:block/stone", greenPng()));
        assertThat(source.load(Identifier.parse("minecraft:block/grass"))).isEmpty();
        assertThat(source.substitutions()).isEmpty();
    }
}
