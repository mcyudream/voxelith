package online.yudream.voxelith.tile.domain.atlas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量扩图集：把新贴图追加进**已发布**图集，不动任何老单元格。
 *
 * <p>为什么只能向下加行：老瓦片的 UV 是烘进 glb 的
 * （{@code col = index % cols}、{@code row = index / cols}），一旦 {@code cols}
 * 或 {@code cellSize} 变了，所有已发布瓦片的贴图会整体错位。保持宽度与列数不变、
 * 高度按行增长，则老单元格的 UV 分毫不动，新贴图落在新增的行里——这正是
 * {@link AtlasLayout} 把宽高拆开的原因。</p>
 *
 * <p>为什么不能简单重打包：重打包会把老贴图重新排布（顺序、列数都可能变），
 * 已发布的上千片瓦片立刻全部错色，除非把它们一并重渲染——那就不是增量了。</p>
 */
public final class AtlasExpander {

    /**
     * 扩容结果。
     *
     * @param layout   新布局（老单元格序号与列数不变，height 变大）
     * @param argb     新像素（行主序，宽 = layout.width()，高 = layout.height()）
     * @param added    真正加入的贴图 id（顺序 = 分配到的单元格顺序）
     * @param missing  本次请求但取不到像素的贴图 id（仍按兜底格渲染）
     * @param unchanged true = 无需扩容（请求的贴图都已在布局里），此时 argb 原样返回
     */
    public record Expansion(AtlasLayout layout, int[] argb, List<String> added,
                            List<String> missing, boolean unchanged) {
    }

    /**
     * @param layout    已发布布局
     * @param argb      已发布像素（长度 = width × height）
     * @param additions 待追加贴图（已按调用方顺序排好，通常按 id 排序保证确定性）
     */
    public Expansion expand(AtlasLayout layout, int[] argb, List<AtlasTexture> additions) {
        Map<String, Integer> cellIndex = new LinkedHashMap<>(layout.cellIndex());
        List<AtlasTexture> pending = new ArrayList<>();
        for (AtlasTexture texture : additions) {
            String id = texture.id().toString();
            if (!cellIndex.containsKey(id)) {
                pending.add(texture);
            }
        }
        if (pending.isEmpty()) {
            return new Expansion(layout, argb, List.of(), List.of(), true);
        }
        if (argb.length != layout.width() * layout.height()) {
            throw new IllegalArgumentException("图集像素尺寸与布局不符: 像素 " + argb.length
                    + "，布局 " + layout.width() + "×" + layout.height());
        }

        // 下一个空闲单元格序号：打包器保证序号 0..n-1 稠密（第 0 格是缺失贴图兜底），
        // 但布局文件可能由外部工具生成，所以按「已用序号的最大值 + 1」推算更稳
        int firstIndex = 0;
        for (int index : layout.cellIndex().values()) {
            firstIndex = Math.max(firstIndex, index + 1);
        }
        int lastIndex = firstIndex + pending.size() - 1;
        int requiredRows = Math.max(layout.rows(), lastIndex / layout.cols() + 1);
        int height = requiredRows * layout.cellSize();
        int width = layout.width();

        int[] pixels = argb;
        if (height > layout.height()) {
            pixels = new int[width * height];
            for (int y = 0; y < layout.height(); y++) {
                System.arraycopy(argb, y * width, pixels, y * width, width);
            }
        }

        List<String> added = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            int index = firstIndex + i;
            AtlasTexture texture = pending.get(i);
            int col = index % layout.cols();
            int row = index / layout.cols();
            copyInto(texture, pixels, width, col * layout.cellSize(), row * layout.cellSize(),
                    layout.cellSize());
            cellIndex.put(texture.id().toString(), index);
            added.add(texture.id().toString());
        }
        return new Expansion(
                new AtlasLayout(layout.cellSize(), layout.cols(), width, height, cellIndex),
                pixels, List.copyOf(added), List.of(), false);
    }

    /** 与 {@link AtlasPacker} 同一套像素拷贝规则（混合分辨率按最近邻缩放填满单元格）。 */
    private static void copyInto(AtlasTexture texture, int[] atlas, int atlasWidth, int baseX, int baseY,
                                 int cellSize) {
        if (texture.cellWidth() == cellSize && texture.cellHeight() == cellSize) {
            for (int y = 0; y < cellSize; y++) {
                System.arraycopy(texture.argb(), y * cellSize,
                        atlas, (baseY + y) * atlasWidth + baseX, cellSize);
            }
            return;
        }
        for (int y = 0; y < cellSize; y++) {
            int srcY = y * texture.cellHeight() / cellSize;
            int dst = (baseY + y) * atlasWidth + baseX;
            for (int x = 0; x < cellSize; x++) {
                int srcX = x * texture.cellWidth() / cellSize;
                atlas[dst + x] = texture.argb()[srcY * texture.cellWidth() + srcX];
            }
        }
    }
}
