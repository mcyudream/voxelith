package online.yudream.voxelith.tile.domain.atlas;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * 增量扩图集：老单元格必须**分毫不动**（否则已发布瓦片的 UV 全部错位），
 * 新贴图落到空位里；容量用满时向下加行且宽度不变。
 */
class AtlasExpanderTest {

    /** 造一张 4 行、每格 16px 的 64×64 图集。 */
    private static AtlasLayout layout(int usedCells, int cols) {
        Map<String, Integer> cells = new LinkedHashMap<>();
        for (int i = 0; i < usedCells; i++) {
            // Identifier.parse 会把无命名空间的 id 补成 minecraft:*，布局键用的是规范化后的 id
            cells.put("minecraft:t" + i, i);
        }
        return new AtlasLayout(16, cols, 64, 64, cells);
    }

    private static int[] pixels(int size, int color) {
        int[] argb = new int[size * size];
        Arrays.fill(argb, color);
        return argb;
    }

    private static AtlasTexture texture(String id, int color) {
        return new AtlasTexture(Identifier.parse(id), 16, 16, pixels(16, color));
    }

    @Test
    @DisplayName("有空位时原地追加：宽高不变，老单元格像素与序号不变")
    void appendsIntoExistingSlack() {
        // cols=4、height 64/16=4 行 → 容量 16；已用 5 格
        AtlasLayout before = layout(5, 4);
        int[] argb = pixels(64, 0xFF112233);
        // 标出已有单元格的「颜色」以便校验没被覆盖
        for (int i = 0; i < 5; i++) {
            argb[(i / 4) * 16 * 64 + (i % 4) * 16] = 0xFF000000 | i;
        }

        AtlasExpander.Expansion expansion = new AtlasExpander().expand(before, argb,
                List.of(texture("minecraft:new_block", 0xFFABCDEF), texture("mod:other", 0xFF123456)));

        assertThat(expansion.unchanged()).isFalse();
        assertThat(expansion.added()).containsExactly("minecraft:new_block", "mod:other");
        // 尺寸与列数不变，老单元格序号也不变（只有新增格子进了 cellIndex）
        assertThat(expansion.layout().width()).isEqualTo(64);
        assertThat(expansion.layout().height()).isEqualTo(64);
        assertThat(expansion.layout().cols()).isEqualTo(before.cols());
        assertThat(expansion.layout().cellIndex().get("minecraft:t4")).isEqualTo(4);
        assertThat(expansion.layout().cellIndex().get("minecraft:new_block")).isEqualTo(5);
        // 新格子画在图集里（序号 5 → 行 1、列 1），且没有覆盖老格子
        int newCellOffset = (5 / 4) * 16 * 64 + (5 % 4) * 16;
        assertThat(expansion.argb()[newCellOffset]).isEqualTo(0xFFABCDEF);
        for (int i = 0; i < 5; i++) {
            assertThat(expansion.argb()[(i / 4) * 16 * 64 + (i % 4) * 16])
                    .as("老单元格 %d 被覆盖", i)
                    .isEqualTo(0xFF000000 | i);
        }
        // 新格子的 UV：列号 = 序号 % cols，行号 = 序号 / cols
        assertThat(expansion.layout().mapUv("minecraft:new_block", 0, 0)[0])
                .isCloseTo(((5 % 4) * 16 + 0.5f) / 64f, offset(1e-4f));
        assertThat(expansion.layout().mapUv("minecraft:new_block", 0, 0)[1])
                .isCloseTo(((5 / 4) * 16 + 0.5f) / 64f, offset(1e-4f));
    }

    @Test
    @DisplayName("容量用满时向下加行：宽度与列数不变、高度增长、老序号不动")
    void growsRowsWhenFull() {
        AtlasLayout before = layout(16, 4);   // 容量刚好用满
        int[] argb = pixels(64, 0xFF0F0F0F);

        AtlasExpander.Expansion expansion = new AtlasExpander().expand(before, argb,
                List.of(texture("minecraft:t16", 0xFF00FF00), texture("minecraft:t17", 0xFF0000FF)));

        assertThat(expansion.layout().cols()).isEqualTo(4);
        assertThat(expansion.layout().width()).isEqualTo(64);
        assertThat(expansion.layout().height()).isEqualTo(80);   // 5 行 × 16px（序号 17 落在第 5 行）
        assertThat(expansion.layout().cellIndex().get("minecraft:t16")).isEqualTo(16);
        assertThat(expansion.layout().cellIndex().get("minecraft:t17")).isEqualTo(17);
        // 老像素按行拷贝进更高的画布，行距仍是原来的宽（64）
        assertThat(expansion.argb().length).isEqualTo(64 * 80);
        assertThat(expansion.argb()[0]).isEqualTo(0xFF0F0F0F);
        assertThat(expansion.argb()[4 * 16 * 64]).isEqualTo(0xFF00FF00);
        assertThat(expansion.argb()[4 * 16 * 64 + 16]).isEqualTo(0xFF0000FF);
        // 高度变大只影响 v，不影响 u
        assertThat(expansion.layout().mapUv("minecraft:t17", 0, 0)[1])
                .isCloseTo((4 * 16 + 0.5f) / 80f, offset(1e-4f));
    }

    @Test
    @DisplayName("贴图已在图集里：不做任何改动")
    void noOpWhenEverythingPresent() {
        AtlasLayout before = layout(3, 4);
        int[] argb = pixels(64, 0xFF998877);
        AtlasExpander.Expansion expansion = new AtlasExpander().expand(before, argb,
                List.of(texture("minecraft:t1", 0xFFFFFFFF)));
        assertThat(expansion.unchanged()).isTrue();
        assertThat(expansion.added()).isEmpty();
        assertThat(expansion.argb()).isSameAs(argb);
    }
}
