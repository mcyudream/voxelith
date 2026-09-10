package online.yudream.voxelith.tile.domain.atlas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图集打包领域服务：均匀网格布局 + 像素拷贝（纯内存运算，无 IO）。
 * 缺失贴图由调用方注入品红兜底后进入同一布局。
 */
public final class AtlasPacker {

    /**
     * @param textures 贴图列表（顺序即布局顺序，调用方应排序保证确定性）
     * @return 打包结果（布局 + 像素）
     */
    public AtlasResult pack(List<AtlasTexture> textures) {
        if (textures.isEmpty()) {
            throw new IllegalArgumentException("图集不能为空");
        }
        int cellSize = 1;
        for (AtlasTexture texture : textures) {
            cellSize = Math.max(cellSize, Math.max(texture.cellWidth(), texture.cellHeight()));
        }

        int cols = (int) Math.ceil(Math.sqrt(textures.size()));
        int rows = (textures.size() + cols - 1) / cols;
        int pixelSize = nextPow2(Math.max(cols, rows) * cellSize);

        int[] atlas = new int[pixelSize * pixelSize];
        Map<String, Integer> cellIndex = new LinkedHashMap<>();
        for (int i = 0; i < textures.size(); i++) {
            AtlasTexture texture = textures.get(i);
            int col = i % cols;
            int row = i / cols;
            cellIndex.put(texture.id().toString(), i);
            copyInto(texture, atlas, pixelSize, col * cellSize, row * cellSize, cellSize);
        }
        return new AtlasResult(new AtlasLayout(cellSize, cols, pixelSize, cellIndex), pixelSize, atlas);
    }

    private static void copyInto(AtlasTexture texture, int[] atlas, int atlasSize, int baseX, int baseY,
                                 int cellSize) {
        if (texture.cellWidth() == cellSize && texture.cellHeight() == cellSize) {
            for (int y = 0; y < cellSize; y++) {
                int src = y * cellSize;
                int dst = (baseY + y) * atlasSize + baseX;
                System.arraycopy(texture.argb(), src, atlas, dst, cellSize);
            }
            return;
        }
        // 混合分辨率（如 32px 的 water_flow 与 16px 普通贴图共存）：
        // UV 映射假定贴图铺满整个单元格，非等尺寸贴图按最近邻缩放填充。
        for (int y = 0; y < cellSize; y++) {
            int srcY = y * texture.cellHeight() / cellSize;
            int dst = (baseY + y) * atlasSize + baseX;
            for (int x = 0; x < cellSize; x++) {
                int srcX = x * texture.cellWidth() / cellSize;
                atlas[dst + x] = texture.argb()[srcY * texture.cellWidth() + srcX];
            }
        }
    }

    private static int nextPow2(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    /**
     * @param layout    布局
     * @param pixelSize 图集边长
     * @param argb      图集像素（行主序 argb）
     */
    public record AtlasResult(AtlasLayout layout, int pixelSize, int[] argb) {
    }
}
