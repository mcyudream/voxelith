package online.yudream.voxelith.tile.domain.atlas;

import java.util.Map;

/**
 * 图集布局：均匀网格打包的结果。
 *
 * <p>为什么宽高分开：**增量扩图集**要往已发布图集里追加新贴图而不改变老单元格的位置
 * （已发布瓦片的 UV 是烘死的）。改 {@code cols} 会让 {@code index % cols} 全体错位，
 * 所以扩容只能**向下加行**——宽度不变、高度增长。全量打包仍然是正方形。</p>
 *
 * @param cellSize  单元格边长（像素，取全部贴图的最大边长）
 * @param cols      列数（扩容时保持不变）
 * @param width     图集宽（像素，2 的幂）
 * @param height    图集高（像素，≥ width；增量扩容后大于宽度）
 * @param cellIndex 贴图 id → 单元格序号（行主序）
 */
public record AtlasLayout(int cellSize, int cols, int width, int height, Map<String, Integer> cellIndex) {

    public AtlasLayout {
        if (cellSize <= 0 || cols <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图集布局参数必须为正: cellSize=" + cellSize
                    + ", cols=" + cols + ", width=" + width + ", height=" + height);
        }
        // 保序不可变：扩图集按序号追加，布局文件的顺序要稳定（Map.copyOf 不保证顺序）
        cellIndex = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(cellIndex));
    }

    /** 兼容构造：正方形图集（全量打包与老布局文件）。 */
    public AtlasLayout(int cellSize, int cols, int pixelSize, Map<String, Integer> cellIndex) {
        this(cellSize, cols, pixelSize, pixelSize, cellIndex);
    }

    /** 图集宽（历史命名：正方形时代的「边长」）。 */
    public int pixelSize() {
        return width;
    }

    /** 当前布局可容纳的单元格数（宽度方向的行数上限按 rows 走，见 {@link #rows()}）。 */
    public int capacity() {
        return cols * rows();
    }

    /** 当前图集的行数。 */
    public int rows() {
        return height / cellSize;
    }

    /**
     * 把贴图局部 uv（0~16）映射为图集归一化 uv。
     * glTF v 轴向下，与 PNG 行序一致，无需翻转。
     *
     * <p>uv 边界内缩半纹素：面边缘的插值 uv 若恰好落在单元格边界上，最近邻采样会
     * floor 到相邻单元格的首纹素，在方块接缝处形成虚线状渗色条纹。内缩后边缘 uv
     * 落在本格外侧纹素中心，杜绝跨格采样。
     */
    public float[] mapUv(String textureId, float u, float v) {
        Integer index = cellIndex.get(textureId);
        if (index == null) {
            // 缺失贴图映射到 (0,0) 格左上角（配合品红兜底贴图）
            return new float[]{0f, 0f};
        }
        int col = index % cols;
        int row = index / cols;
        float pu = (col * cellSize + 0.5f + u / 16f * (cellSize - 1f)) / width;
        float pv = (row * cellSize + 0.5f + v / 16f * (cellSize - 1f)) / height;
        return new float[]{pu, pv};
    }
}
