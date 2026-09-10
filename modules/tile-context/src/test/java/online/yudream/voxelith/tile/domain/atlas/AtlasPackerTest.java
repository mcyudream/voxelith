package online.yudream.voxelith.tile.domain.atlas;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasPackerTest {

    private static AtlasTexture solid(String path, int argb) {
        int[] pixels = new int[256];
        java.util.Arrays.fill(pixels, argb);
        return new AtlasTexture(Identifier.minecraft(path), 16, 16, pixels);
    }

    @Test
    void threeTexturesPackInto32x32() {
        AtlasPacker.AtlasResult result = new AtlasPacker().pack(List.of(
                solid("a", 0xFFFF0000), solid("b", 0xFF00FF00), solid("c", 0xFF0000FF)));

        assertEquals(16, result.layout().cellSize());
        assertEquals(2, result.layout().cols());
        assertEquals(32, result.pixelSize());
        assertEquals(3, result.layout().cellIndex().size());
        // 第 0 格左上角为第一张图的颜色
        assertEquals(0xFFFF0000, result.argb()[0]);
        // 第 1 格（col=1,row=0）左上角
        assertEquals(0xFF00FF00, result.argb()[16]);
        // 第 2 格（col=0,row=1）左上角
        assertEquals(0xFF0000FF, result.argb()[16 * 32]);
    }

    @Test
    void mapUvMapsLocalUvIntoCell() {
        AtlasPacker.AtlasResult result = new AtlasPacker().pack(List.of(
                solid("a", 0xFFFFFFFF), solid("b", 0xFFFFFFFF)));

        // cell 1 → col=1,row=0；内缩半纹素：u=0,v=0 → 像素 (16.5,0.5) / 32
        assertArrayEquals(new float[]{16.5f / 32f, 0.5f / 32f},
                result.layout().mapUv("minecraft:b", 0f, 0f), 1e-6f);
        // u=16,v=16 → 像素 (31.5,15.5) / 32
        assertArrayEquals(new float[]{31.5f / 32f, 15.5f / 32f},
                result.layout().mapUv("minecraft:b", 16f, 16f), 1e-6f);
        // 缺失贴图 → (0,0) 兜底格
        assertArrayEquals(new float[]{0f, 0f},
                result.layout().mapUv("minecraft:missing", 8f, 8f), 1e-6f);
    }

    @Test
    void mixedResolutionsAreRescaledToFillCell() {
        // 32px 贴图（如 water_flow 32×1024 的第一帧）与 16px 贴图共存：
        // 16px 贴图必须最近邻放大铺满 32px 单元格，否则 UV 采样落在透明区
        int[] wide = new int[32 * 32];
        java.util.Arrays.fill(wide, 0xFF123456);
        AtlasPacker.AtlasResult result = new AtlasPacker().pack(List.of(
                new AtlasTexture(Identifier.minecraft("flow"), 32, 32, wide),
                solid("small", 0xFFABCDEF)));

        assertEquals(32, result.layout().cellSize());
        assertEquals(2, result.layout().cols());
        assertEquals(64, result.pixelSize());
        // 小贴图格（col=1,row=0，原点 (32,0)）整格铺满：四角与中心均为其颜色
        int base = 32;
        assertEquals(0xFFABCDEF, result.argb()[base]);                       // (32, 0)
        assertEquals(0xFFABCDEF, result.argb()[base + 31]);                  // (63, 0)
        assertEquals(0xFFABCDEF, result.argb()[31 * 64 + base]);             // (32, 31)
        assertEquals(0xFFABCDEF, result.argb()[31 * 64 + base + 31]);        // (63, 31)
        assertEquals(0xFFABCDEF, result.argb()[16 * 64 + base + 16]);        // 中心
        // 大贴图格原样
        assertEquals(0xFF123456, result.argb()[0]);
    }
}
