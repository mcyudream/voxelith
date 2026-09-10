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
