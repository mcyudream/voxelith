package online.yudream.voxelith.tile.domain.atlas;

import java.util.Map;

/**
 * 图集布局：均匀网格打包的结果。
 *
 * @param cellSize  单元格边长（像素，取全部贴图的最大边长）
 * @param cols      列数
 * @param pixelSize 图集边长（2 的幂）
 * @param cellIndex 贴图 id → 单元格序号（行主序）
 */
public record AtlasLayout(int cellSize, int cols, int pixelSize, Map<String, Integer> cellIndex) {

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
        float pu = (col * cellSize + 0.5f + u / 16f * (cellSize - 1f)) / pixelSize;
        float pv = (row * cellSize + 0.5f + v / 16f * (cellSize - 1f)) / pixelSize;
        return new float[]{pu, pv};
    }
}
