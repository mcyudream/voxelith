package online.yudream.voxelith.lod.domain.heightfield;

import java.util.Arrays;
import java.util.List;

/**
 * 某一 LOD 层级的全局柱状高度场：每柱覆盖 footprint×footprint 方块（footprint = 2^level），
 * 记录柱顶高度（最高朝上表面的 y）与该柱颜色（取最高采样点的颜色）。
 * 柱坐标为全局坐标：柱 (cx, cz) 覆盖世界方块 [cx*footprint, (cx+1)*footprint)。
 * 上层金字塔由下层 2×2 聚合（柱顶取最大，颜色随最高柱传递）。
 */
public final class Heightfield {

    private final int footprint;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    /** 行主序 [cz-originZ][cx-originX]；NaN = 空柱（无任何朝上表面）。 */
    private final float[] topY;
    private final int[] rgb;
    /** 全场最低柱顶（边界裙墙下探基准）。 */
    private final float floorY;

    private Heightfield(int footprint, int originX, int originZ, int width, int depth,
                        float[] topY, int[] rgb, float floorY) {
        this.footprint = footprint;
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.depth = depth;
        this.topY = topY;
        this.rgb = rgb;
        this.floorY = floorY;
    }

    /** 由表面采样点构建指定 footprint 的高度场（取每柱最高采样，颜色随最高点）。 */
    public static Heightfield fromSamples(List<LodSample> samples, int footprint) {
        if (samples.isEmpty()) {
            return new Heightfield(footprint, 0, 0, 0, 0, new float[0], new int[0], 0);
        }
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE;
        int maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (LodSample sample : samples) {
            int cx = Math.floorDiv((int) Math.floor(sample.x()), footprint);
            int cz = Math.floorDiv((int) Math.floor(sample.z()), footprint);
            minCx = Math.min(minCx, cx);
            maxCx = Math.max(maxCx, cx);
            minCz = Math.min(minCz, cz);
            maxCz = Math.max(maxCz, cz);
        }
        int width = maxCx - minCx + 1;
        int depth = maxCz - minCz + 1;
        float[] topY = new float[width * depth];
        Arrays.fill(topY, Float.NaN);
        int[] rgb = new int[width * depth];
        float floorY = Float.MAX_VALUE;
        for (LodSample sample : samples) {
            int cx = Math.floorDiv((int) Math.floor(sample.x()), footprint);
            int cz = Math.floorDiv((int) Math.floor(sample.z()), footprint);
            int index = (cz - minCz) * width + (cx - minCx);
            if (Float.isNaN(topY[index]) || sample.y() > topY[index]) {
                topY[index] = sample.y();
                rgb[index] = sample.rgb();
            }
        }
        for (float y : topY) {
            if (!Float.isNaN(y)) {
                floorY = Math.min(floorY, y);
            }
        }
        return new Heightfield(footprint, minCx, minCz, width, depth, topY, rgb, floorY);
    }

    /** 聚合出上一层（footprint ×2）：父柱 = 2×2 子柱中柱顶最高者，颜色随之传递。 */
    public Heightfield aggregate() {
        int parentFootprint = footprint * 2;
        int pOriginX = Math.floorDiv(originX, 2);
        int pOriginZ = Math.floorDiv(originZ, 2);
        int pWidth = Math.floorDiv(originX + width - 1, 2) - pOriginX + 1;
        int pDepth = Math.floorDiv(originZ + depth - 1, 2) - pOriginZ + 1;
        float[] pTopY = new float[pWidth * pDepth];
        Arrays.fill(pTopY, Float.NaN);
        int[] pRgb = new int[pWidth * pDepth];
        float pFloorY = Float.MAX_VALUE;
        for (int pz = 0; pz < pDepth; pz++) {
            for (int px = 0; px < pWidth; px++) {
                int baseCx = (pOriginX + px) * 2;
                int baseCz = (pOriginZ + pz) * 2;
                float best = Float.NaN;
                int bestRgb = 0;
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        float childY = topY(baseCx + dx, baseCz + dz);
                        if (!Float.isNaN(childY) && (Float.isNaN(best) || childY > best)) {
                            best = childY;
                            bestRgb = rgb(baseCx + dx, baseCz + dz);
                        }
                    }
                }
                int index = pz * pWidth + px;
                pTopY[index] = best;
                pRgb[index] = bestRgb;
                if (!Float.isNaN(best)) {
                    pFloorY = Math.min(pFloorY, best);
                }
            }
        }
        return new Heightfield(parentFootprint, pOriginX, pOriginZ, pWidth, pDepth, pTopY, pRgb,
                pFloorY == Float.MAX_VALUE ? 0 : pFloorY);
    }

    public int footprint() {
        return footprint;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public float floorY() {
        return floorY;
    }

    /** 柱顶高度（全局柱坐标）；网格外或空柱返回 NaN。 */
    public float topY(int cx, int cz) {
        if (cx < originX || cx >= originX + width || cz < originZ || cz >= originZ + depth) {
            return Float.NaN;
        }
        return topY[(cz - originZ) * width + (cx - originX)];
    }

    /** 柱颜色（全局柱坐标）；网格外或空柱返回 0。 */
    public int rgb(int cx, int cz) {
        if (cx < originX || cx >= originX + width || cz < originZ || cz >= originZ + depth) {
            return 0;
        }
        return rgb[(cz - originZ) * width + (cx - originX)];
    }

    /** 覆盖的瓦片坐标范围（每瓦片 columnsPerTile 柱，瓦片原点与全局柱网格对齐）。 */
    public int minTileX(int columnsPerTile) {
        return Math.floorDiv(originX, columnsPerTile);
    }

    public int maxTileX(int columnsPerTile) {
        return Math.floorDiv(originX + width - 1, columnsPerTile);
    }

    public int minTileZ(int columnsPerTile) {
        return Math.floorDiv(originZ, columnsPerTile);
    }

    public int maxTileZ(int columnsPerTile) {
        return Math.floorDiv(originZ + depth - 1, columnsPerTile);
    }
}
