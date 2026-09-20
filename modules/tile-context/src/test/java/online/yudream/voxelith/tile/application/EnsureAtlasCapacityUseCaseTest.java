package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;
import online.yudream.voxelith.tile.infrastructure.artifact.FilePublishedAtlas;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.infrastructure.image.PngImageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
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

    /**
     * 回归：扩容会改变图集尺寸（向下加行 → 非正方形），清单里的 `atlas` 必须跟着更新，
     * 否则 PNG 与清单声明不一致，`voxelith-forge audit` 会误判
     * {@code atlas-size-mismatch}（渲染其实是对的）。
     */
    @Test
    @DisplayName("扩容后同步清单里的图集宽高（清单存在时）")
    void expansionSyncsManifestAtlasRef(@TempDir Path publishRoot) {
        FilePublishedAtlas published = new FilePublishedAtlas(publishRoot);
        FileManifestStore manifests = new FileManifestStore(publishRoot);
        AtlasLayout before = publishedLayout();
        published.save("m", new online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult(
                before, before.width(), new int[before.width() * before.height()]), publishedPng());
        manifests.save("m", manifestWithAtlas(before.width(), before.height(), before.cellIndex().size()));

        TexturePixelSource pixels = id -> Optional.of(texture(id.toString(), 0xFF00FF00));
        EnsureAtlasCapacityUseCase useCase = new EnsureAtlasCapacityUseCase(
                published, manifests, pixels, new PngImageCodec());

        // 16 格已用满，再加两片（序号 16、17 落在第 5 行）→ 高度从 64 长到 80
        Map<String, Integer> cells = new LinkedHashMap<>();
        for (int i = 0; i < 16; i++) {
            cells.put("t" + i, i);
        }
        AtlasLayout full = new AtlasLayout(CELL, 4, 64, 64, cells);
        published.save("m", new online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult(
                full, full.width(), new int[64 * 64]), publishedPng());
        manifests.save("m", manifestWithAtlas(64, 64, 16));

        EnsureAtlasCapacityUseCase.Result result = useCase.ensure("m",
                new AtlasReuse(full, publishedPng()),
                List.of("mod:a", "mod:b"));

        assertThat(result.expanded()).isTrue();
        assertThat(result.atlas().layout().height()).isEqualTo(80);
        MapManifest manifest = manifests.load("m").orElseThrow();
        assertThat(manifest.atlas().size()).isEqualTo(64);
        assertThat(manifest.atlas().height())
                .as("清单必须报告非正方形图集的真实高度")
                .isEqualTo(80);
        assertThat(manifest.atlas().textureCount()).isEqualTo(18);
    }

    private static MapManifest manifestWithAtlas(int width, int height, int textureCount) {
        return new MapManifest(1, "m", "m", "v1", Instant.now().toString(),
                new MapManifest.Settings(32, 2),
                new float[]{0, 0, 0}, new float[]{64, 64, 64},
                new MapManifest.AtlasRef("atlas.png", width, textureCount, height),
                List.of());
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
