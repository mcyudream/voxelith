package online.yudream.voxelith.lod.domain.heightfield;

import online.yudream.voxelith.sharedkernel.color.ColorSpace;

import java.util.Arrays;

/**
 * 世界 XZ 航拍色图：每格 1 方块，最高朝上表面的线性 RGB。
 * LOD 瓦片从中切出并盒式下采样，作为柱顶 UV 色图（图像压缩式保细节，而非整柱单色）。
 */
public final class AerialRaster {

    public static final int TILE_TEXTURE_SIZE = 128;

    private static final float EMPTY = Float.NEGATIVE_INFINITY;

    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final float[] topY;
    private final float[] wr;
    private final float[] wg;
    private final float[] wb;
    private final float[] wsum;

    public AerialRaster(int originX, int originZ, int width, int depth) {
        this.originX = originX;
        this.originZ = originZ;
        this.width = Math.max(width, 0);
        this.depth = Math.max(depth, 0);
        int n = Math.multiplyExact(this.width, this.depth);
        this.topY = new float[n];
        Arrays.fill(this.topY, EMPTY);
        this.wr = new float[n];
        this.wg = new float[n];
        this.wb = new float[n];
        this.wsum = new float[n];
    }

    public static AerialRaster empty() {
        return new AerialRaster(0, 0, 0, 0);
    }

    public boolean isEmpty() {
        return width <= 0 || depth <= 0;
    }

    /**
     * 把朝上表面的线性色按 XZ 包围盒溅到栅格。同格只保留最高表面；
     * 同高（1.5 格内）按面积加权，避免花斑尖端盖住屋顶。
     */
    public void splat(float minX, float maxX, float minZ, float maxZ, float y, int linearRgb, float area) {
        if (isEmpty() || maxX <= minX || maxZ <= minZ) {
            return;
        }
        int x0 = Math.max(0, (int) Math.floor(minX) - originX);
        int x1 = Math.min(width, (int) Math.ceil(maxX) - originX);
        int z0 = Math.max(0, (int) Math.floor(minZ) - originZ);
        int z1 = Math.min(depth, (int) Math.ceil(maxZ) - originZ);
        if (x0 >= x1 || z0 >= z1) {
            return;
        }
        float weight = Math.max(area, 0.01f);
        float r = (linearRgb >> 16) & 0xFF;
        float g = (linearRgb >> 8) & 0xFF;
        float b = linearRgb & 0xFF;
        for (int z = z0; z < z1; z++) {
            int row = z * width;
            for (int x = x0; x < x1; x++) {
                int i = row + x;
                float current = topY[i];
                if (y > current + 1.5f) {
                    topY[i] = y;
                    wr[i] = r * weight;
                    wg[i] = g * weight;
                    wb[i] = b * weight;
                    wsum[i] = weight;
                } else if (y >= current - 1.5f) {
                    if (y > current) {
                        topY[i] = y;
                    }
                    wr[i] += r * weight;
                    wg[i] += g * weight;
                    wb[i] += b * weight;
                    wsum[i] += weight;
                }
            }
        }
    }

    /**
     * 切出覆盖 {@code [worldX, worldX+coverage) × [worldZ, worldZ+coverage)} 的 sRGB ARGB 色图，
     * 盒式平均（线性空间）再转 sRGB，供 PNG 嵌入。
     */
    public int[] downsample(int worldX, int worldZ, int coverage, int texSize) {
        int[] argb = new int[texSize * texSize];
        if (isEmpty() || coverage <= 0) {
            return argb;
        }
        double scale = coverage / (double) texSize;
        for (int tz = 0; tz < texSize; tz++) {
            double z0 = worldZ + tz * scale;
            double z1 = z0 + scale;
            for (int tx = 0; tx < texSize; tx++) {
                double x0 = worldX + tx * scale;
                double x1 = x0 + scale;
                int packed = boxAverage(x0, x1, z0, z1);
                argb[tz * texSize + tx] = packed;
            }
        }
        return argb;
    }

    private int boxAverage(double x0, double x1, double z0, double z1) {
        int ix0 = Math.max(0, (int) Math.floor(x0) - originX);
        int ix1 = Math.min(width, (int) Math.ceil(x1) - originX);
        int iz0 = Math.max(0, (int) Math.floor(z0) - originZ);
        int iz1 = Math.min(depth, (int) Math.ceil(z1) - originZ);
        if (ix0 >= ix1 || iz0 >= iz1) {
            return 0;
        }
        double r = 0, g = 0, b = 0, w = 0;
        for (int z = iz0; z < iz1; z++) {
            int row = z * width;
            for (int x = ix0; x < ix1; x++) {
                int i = row + x;
                float s = wsum[i];
                if (s <= 0f) {
                    continue;
                }
                r += wr[i];
                g += wg[i];
                b += wb[i];
                w += s;
            }
        }
        if (w <= 0) {
            return 0;
        }
        int ri = clampByte((int) Math.round(r / w));
        int gi = clampByte((int) Math.round(g / w));
        int bi = clampByte((int) Math.round(b / w));
        int srgb = ColorSpace.linearToSrgbRgb((ri << 16) | (gi << 8) | bi);
        return 0xFF000000 | srgb;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
