package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 贴图平均色采样：取贴图不透明像素（alpha ≥ 128）在线性光空间的 RGB 均值，
 * 供 LOD 柱状几何取色。sRGB 字节不能直接平均（伽马编码下平均会偏亮）。
 * 结果按贴图 id 缓存（图集级采样一次即可复用整张金字塔）。
 */
public class TextureColorSampler {

    private final TexturePixelSource pixelSource;
    private final Map<String, Integer> cache = new HashMap<>();

    public TextureColorSampler(TexturePixelSource pixelSource) {
        this.pixelSource = pixelSource;
    }

    /**
     * @return 平均色 0xRRGGBB（线性空间字节，可直接写入 glb COLOR_0）；贴图缺失或无有效不透明像素返回 -1
     */
    public int averageColorRgb(String textureId) {
        return cache.computeIfAbsent(textureId, this::compute);
    }

    /**
     * 对四边形 UV（贴图局部 0~16）包围盒内的不透明纹素做线性均值。
     * 比整张贴图平均更能反映实际露出的区域（草顶 vs 侧面、裁切面）。
     */
    public int sampleUvAverageRgb(String textureId, float[] uvs) {
        Optional<AtlasTexture> texture = pixelSource.load(Identifier.parse(textureId));
        if (texture.isEmpty()) {
            return -1;
        }
        AtlasTexture tex = texture.get();
        int w = tex.cellWidth();
        int h = tex.cellHeight();
        if (uvs == null || uvs.length < 2 || w <= 0 || h <= 0) {
            return averageColorRgb(textureId);
        }
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        int n = uvs.length / 2;
        for (int i = 0; i < n; i++) {
            minU = Math.min(minU, uvs[i * 2]);
            maxU = Math.max(maxU, uvs[i * 2]);
            minV = Math.min(minV, uvs[i * 2 + 1]);
            maxV = Math.max(maxV, uvs[i * 2 + 1]);
        }
        int x0 = clamp((int) Math.floor(minU / 16f * w), 0, w - 1);
        int x1 = clamp((int) Math.ceil(maxU / 16f * w), x0 + 1, w);
        int y0 = clamp((int) Math.floor(minV / 16f * h), 0, h - 1);
        int y1 = clamp((int) Math.ceil(maxV / 16f * h), y0 + 1, h);
        int[] argb = tex.argb();
        double r = 0, g = 0, b = 0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) {
                int pixel = argb[row + x];
                if (((pixel >>> 24) & 0xFF) < 128) {
                    continue;
                }
                r += ColorSpace.srgbChannelToLinear((pixel >> 16) & 0xFF);
                g += ColorSpace.srgbChannelToLinear((pixel >> 8) & 0xFF);
                b += ColorSpace.srgbChannelToLinear(pixel & 0xFF);
                count++;
            }
        }
        if (count == 0) {
            return averageColorRgb(textureId);
        }
        int ri = Math.round((float) (r / count) * 255);
        int gi = Math.round((float) (g / count) * 255);
        int bi = Math.round((float) (b / count) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int compute(String textureId) {
        Optional<AtlasTexture> texture = pixelSource.load(Identifier.parse(textureId));
        if (texture.isEmpty()) {
            return -1;
        }
        double r = 0, g = 0, b = 0;
        long count = 0;
        for (int argb : texture.get().argb()) {
            if (((argb >>> 24) & 0xFF) < 128) {
                continue;
            }
            r += ColorSpace.srgbChannelToLinear((argb >> 16) & 0xFF);
            g += ColorSpace.srgbChannelToLinear((argb >> 8) & 0xFF);
            b += ColorSpace.srgbChannelToLinear(argb & 0xFF);
            count++;
        }
        if (count == 0) {
            return -1;
        }
        int ri = Math.round((float) (r / count) * 255);
        int gi = Math.round((float) (g / count) * 255);
        int bi = Math.round((float) (b / count) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }
}
