package online.yudream.voxelith.lod.domain.atlas;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * LOD 图集页打包：槽位尺寸策略、按世界瓦片网格定位、UV 矩形半纹素内缩。
 * 这些是不依赖渲染的纯逻辑，也是「瓦片 ↔ 槽位」对应关系的唯一真相。
 */
class LodAtlasPackerTest {

    @Test
    void slotSizeHalvesWithLevelAndIsCappedByPageLimit() {
        // L1 基础 64、L2 32、更深受下限约束仍为 32
        assertThat(LodAtlasPacker.of(1, 0, 0, 0, 0).slotSize()).isEqualTo(64);
        assertThat(LodAtlasPacker.of(2, 0, 0, 0, 0).slotSize()).isEqualTo(32);
        assertThat(LodAtlasPacker.of(5, 0, 0, 0, 0).slotSize()).isEqualTo(32);

        // 网格过大时收缩到页面上限内（4096），并对齐到 8 像素
        LodAtlasPacker wide = LodAtlasPacker.of(1, 0, 70, 0, 70);
        assertThat(wide.slotSize()).isEqualTo(56);
        assertThat(wide.width()).isEqualTo(71 * 56);
        assertThat(wide.width()).isLessThanOrEqualTo(LodAtlasPacker.MAX_PAGE_DIM);
        assertThat(wide.height()).isLessThanOrEqualTo(LodAtlasPacker.MAX_PAGE_DIM);

        // 极端网格：退化到下限而不是 0 或负
        assertThat(LodAtlasPacker.of(1, 0, 9999, 0, 0).slotSize()).isEqualTo(LodAtlasPacker.MIN_SLOT);
    }

    @Test
    void pageSizeIsGridTimesSlot() {
        LodAtlasPacker packer = LodAtlasPacker.of(1, 0, 3, 0, 1);
        assertThat(packer.gridWidth()).isEqualTo(4);
        assertThat(packer.gridHeight()).isEqualTo(2);
        assertThat(packer.width()).isEqualTo(4 * 64);
        assertThat(packer.height()).isEqualTo(2 * 64);
        assertThat(packer.newPage()).hasSize(4 * 64 * 2 * 64);
    }

    @Test
    void blitPlacesTileAtItsGridSlot() {
        LodAtlasPacker packer = LodAtlasPacker.of(1, 0, 1, 0, 1);
        int slot = packer.slotSize();
        int[] page = packer.newPage();
        int[] a = solid(slot, 0xFFFF0000);
        int[] b = solid(slot, 0xFF00FF00);

        packer.blit(page, 1, 0, a);
        packer.blit(page, 0, 1, b);

        // (tx=1,tz=0) → col 1 / row 0；(tx=0,tz=1) → col 0 / row 1
        assertThat(page[0]).isZero();                                   // (0,0) 未铺：透明
        assertThat(page[0 * slot + slot]).isEqualTo(0xFFFF0000);        // row 0, col 1 首像素
        assertThat(page[(slot) * packer.width()]).isEqualTo(0xFF00FF00); // row 1, col 0 首像素
    }

    @Test
    void blitRejectsOutOfGridTileAndWrongSizedImage() {
        LodAtlasPacker packer = LodAtlasPacker.of(1, 0, 1, 0, 1);
        int[] page = packer.newPage();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> packer.blit(page, 2, 0, solid(packer.slotSize(), 0xFFFFFFFF)))
                .withMessageContaining("不在图集网格");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> packer.blit(page, 0, 0, new int[3]))
                .withMessageContaining("色图尺寸不符");
    }

    @Test
    void uvRectIsInsetByHalfTexelAndOrdersByRowMajor() {
        LodAtlasPacker packer = LodAtlasPacker.of(1, 0, 1, 0, 1);
        int size = packer.width(); // 2 槽 × 64

        float[] left = packer.uvRect(0, 0);
        float[] right = packer.uvRect(1, 0);
        float[] below = packer.uvRect(0, 1);

        float inset = 0.5f / size;
        assertThat(left[0]).isEqualTo(inset);
        assertThat(left[1]).isEqualTo(inset);
        assertThat(left[2]).isEqualTo(64f / size - inset);
        assertThat(left[3]).isEqualTo(64f / size - inset);

        // 同层相邻槽位：u 区间不重叠（否则会串色到邻瓦片）
        assertThat(right[0]).isGreaterThan(left[2]);
        // 行序：tz 更大 → v 更大（与色图行 0 = 最小世界 Z 对齐）
        assertThat(below[1]).isGreaterThan(left[1]);

        // 内缩必须大于 0，否则线性过滤会跨槽取色
        assertThat(left[0]).isGreaterThan(0f);
    }

    private static int[] solid(int size, int argb) {
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, argb);
        return pixels;
    }
}
