package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.tile.infrastructure.artifact.FilePublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量扩图集用例：新贴图追加进已发布图集并落盘；老贴图的 UV 与像素不受影响。
 */
class EnsureAtlasCapacityUseCaseTest {

    private static final int CELL = 16;

    private static AtlasTexture texture(String id, int color) {
        int[] argb = new int[CELL * CELL];
        java.util.Arrays.fill(argb, color);
        return new AtlasTexture(Identifier.parse(id), CELL, CELL, argb);
    }

    /** 4 列 × 4 行的 64² 图集（容量 16），已用 3 格。 */
    private static AtlasLayout publishedLayout() {
        Map<String, Integer> cells = new LinkedHashMap<>();
        cells.put("missing", 0);
        cells.put("minecraft:block/stone", 1);
        cells.put("minecraft:block/dirt", 2);
        return new AtlasLayout(CELL, 4, 64, 64, cells);
    }

    private static byte[] publishedPng() {
        int[] argb = new int[64 * 64];
        java.util.Arrays.fill(argb, 0xFF335577);
        return new PngImageCodec().encodePng(64, 64, argb);
    }

    @Test
    @DisplayName("缺贴图时追加并写回已发布图集；已有贴图原样返回")
    void expandsAndPersists(@TempDir Path publishRoot) {
        FilePublishedAtlas published = new FilePublishedAtlas(publishRoot);
        AtlasReuse current = new AtlasReuse(publishedLayout(), publishedPng());
        published.save("m", new online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult(
                current.layout(), current.layout().width(), new int[64 * 64]), current.png());

        TexturePixelSource pixels = id -> switch (id.toString()) {
            case "mod:new_block" -> Optional.of(texture("mod:new_block", 0xFF00FF00));
            case "minecraft:block/stone" -> Optional.of(texture("minecraft:block/stone", 0xFFFF0000));
            default -> Optional.empty();
        };
        EnsureAtlasCapacityUseCase useCase =
                new EnsureAtlasCapacityUseCase(published, pixels, new PngImageCodec());

        EnsureAtlasCapacityUseCase.Result result = useCase.ensure("m", current,
                List.of("minecraft:block/stone", "mod:new_block", "mod:not_in_pack"));

        assertThat(result.expanded()).isTrue();
        assertThat(result.added()).containsExactly("mod:new_block");
        assertThat(result.missing()).containsExactly("mod:not_in_pack");
        // 老格子序号不动（已发布瓦片 UV 依旧有效），新贴图落在下一个空位
        assertThat(result.atlas().layout().cellIndex()) 
                .containsEntry("minecraft:block/stone", 1)
                .containsEntry("minecraft:block/dirt", 2)
                .containsEntry("mod:new_block", 3);
        assertThat(result.atlas().layout().width()).isEqualTo(64);
        assertThat(result.atlas().layout().height()).isEqualTo(64);

        // 写回已发布目录：下次增量/前端直接用新图集
        AtlasReuse reloaded = published.load("m").orElseThrow();
        assertThat(reloaded.layout().cellIndex()).containsEntry("mod:new_block", 3);
        int[] pixelsOut = new PngImageCodec().decodePng(reloaded.png());
        assertThat(pixelsOut[3 * CELL]).isEqualTo(0xFF00FF00);
        assertThat(pixelsOut[1 * CELL]).isEqualTo(0xFF335577);   // 老格子的像素来自已发布图集
    }

    @Test
    @DisplayName("贴图都在图集里 / 图集为空时都不做 IO")
    void noOpPaths(@TempDir Path publishRoot) {
        FilePublishedAtlas published = new FilePublishedAtlas(publishRoot);
        TexturePixelSource pixels = id -> Optional.of(texture(id.toString(), 0xFF00FF00));
        EnsureAtlasCapacityUseCase useCase =
                new EnsureAtlasCapacityUseCase(published, pixels, new PngImageCodec());

        AtlasReuse current = new AtlasReuse(publishedLayout(), publishedPng());
        assertThat(useCase.ensure("m", current, List.of("minecraft:block/stone")).expanded()).isFalse();
        assertThat(useCase.ensure("m", current, List.of()).expanded()).isFalse();
        assertThat(useCase.ensure("m", null, List.of("mod:x")).atlas()).isNull();
        assertThat(published.load("m")).isEmpty();
    }

    @Test
    @DisplayName("请求的贴图资源包里一个都没有：不扩容（保持品红兜底语义）")
    void allMissingKeepsAtlasUntouched(@TempDir Path publishRoot) {
        FilePublishedAtlas published = new FilePublishedAtlas(publishRoot);
        AtlasReuse current = new AtlasReuse(publishedLayout(), publishedPng());
        EnsureAtlasCapacityUseCase useCase = new EnsureAtlasCapacityUseCase(
                published, id -> Optional.empty(), new PngImageCodec());

        EnsureAtlasCapacityUseCase.Result result =
                useCase.ensure("m", current, List.of("mod:gone"));

        assertThat(result.expanded()).isFalse();
        assertThat(result.missing()).containsExactly("mod:gone");
        assertThat(result.atlas()).isSameAs(current);
        assertThat(published.load("m")).isEmpty();
    }
}
