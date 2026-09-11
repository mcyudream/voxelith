package online.yudream.voxelith.lod.domain.heightfield;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 柱顶以下多少高度内的表面参与颜色投票（花/树冠尖端不覆盖整柱颜色）。
     * 高度仍取柱内最高有效表面。顶面视觉颜色由 {@code AerialRaster} 航拍 mip 承担。
     */
    private static final float COLOR_BAND = 1.5f;

    /** 由表面采样点构建指定 footprint 的高度场（柱顶高度取最高，颜色取柱顶附近面积加权多数色）。 */
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
        for (LodSample sample : samples) {
            int cx = Math.floorDiv((int) Math.floor(sample.x()), footprint);
            int cz = Math.floorDiv((int) Math.floor(sample.z()), footprint);
            int index = (cz - minCz) * width + (cx - minCx);
            if (Float.isNaN(topY[index]) || sample.y() > topY[index]) {
                topY[index] = sample.y();
            }
        }
        voteColumnColors(samples, footprint, minCx, minCz, width, topY, rgb);
        float floorY = Float.MAX_VALUE;
        for (float y : topY) {
            if (!Float.isNaN(y)) {
                floorY = Math.min(floorY, y);
            }
        }
        return new Heightfield(footprint, minCx, minCz, width, depth, topY, rgb, floorY);
    }

    @SuppressWarnings("unchecked")
    private static void voteColumnColors(List<LodSample> samples, int footprint,
                                         int minCx, int minCz, int width,
                                         float[] topY, int[] rgb) {
        int n = topY.length;
        Map<Integer, float[]>[] buckets = new Map[n];
        for (LodSample sample : samples) {
            int cx = Math.floorDiv((int) Math.floor(sample.x()), footprint);
            int cz = Math.floorDiv((int) Math.floor(sample.z()), footprint);
            int index = (cz - minCz) * width + (cx - minCx);
            float top = topY[index];
            if (Float.isNaN(top) || sample.y() < top - COLOR_BAND) {
                continue;
            }
            Map<Integer, float[]> bucket = buckets[index];
            if (bucket == null) {
                bucket = new HashMap<>(4);
                buckets[index] = bucket;
            }
            float[] slot = bucket.get(sample.rgb());
            if (slot == null) {
                slot = new float[]{0f};
                bucket.put(sample.rgb(), slot);
            }
            slot[0] += Math.max(sample.areaXz(), 0.01f);
        }
        for (int i = 0; i < n; i++) {
            Map<Integer, float[]> bucket = buckets[i];
            if (bucket == null) {
                continue;
            }
            int winner = 0;
            float best = -1f;
            for (Map.Entry<Integer, float[]> e : bucket.entrySet()) {
                if (e.getValue()[0] > best) {
                    best = e.getValue()[0];
                    winner = e.getKey();
                }
            }
            rgb[i] = winner;
        }
    }

    /** 二进制仓储还原（长度必须为 width*depth）。 */
    public static Heightfield restore(int footprint, int originX, int originZ, int width, int depth,
                                      float[] topY, int[] rgb, float floorY) {
        if (topY.length != width * depth || rgb.length != width * depth) {
            throw new IllegalArgumentException("高度场数组长度必须为 width*depth");
        }
        return new Heightfield(footprint, originX, originZ, width, depth, topY, rgb, floorY);
    }

    /**
     * 某 region（512×512 方块）在给定 footprint 下覆盖的闭区间柱坐标
     * {@code [minCx, minCz, maxCx, maxCz]}。
     */
    public static int[] regionColumnBounds(int regionX, int regionZ, int footprint) {
        int minBlockX = regionX << 9;
        int maxBlockX = minBlockX + 511;
        int minBlockZ = regionZ << 9;
        int maxBlockZ = minBlockZ + 511;
        return new int[]{
                Math.floorDiv(minBlockX, footprint),
                Math.floorDiv(minBlockZ, footprint),
                Math.floorDiv(maxBlockX, footprint),
                Math.floorDiv(maxBlockZ, footprint)
        };
    }

    /**
     * 把闭区间柱矩形清为空柱（NaN）。无重叠时返回 this。
     * 增量替换 region 时先清再 merge，避免拆除后残留旧柱。
     */
    public Heightfield clearColumns(int minCx, int minCz, int maxCx, int maxCz) {
        if (width == 0 || depth == 0) {
            return this;
        }
        int x0 = Math.max(minCx, originX);
        int x1 = Math.min(maxCx, originX + width - 1);
        int z0 = Math.max(minCz, originZ);
        int z1 = Math.min(maxCz, originZ + depth - 1);
        if (x0 > x1 || z0 > z1) {
            return this;
        }
        float[] nextY = topY.clone();
        int[] nextRgb = rgb.clone();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int index = (z - originZ) * width + (x - originX);
                nextY[index] = Float.NaN;
                nextRgb[index] = 0;
            }
        }
        float nextFloor = Float.MAX_VALUE;
        for (float y : nextY) {
            if (!Float.isNaN(y)) {
                nextFloor = Math.min(nextFloor, y);
            }
        }
        return new Heightfield(footprint, originX, originZ, width, depth, nextY, nextRgb,
                nextFloor == Float.MAX_VALUE ? 0 : nextFloor);
    }

    /**
     * 并入另一高度场：并集包围盒，other 覆盖重叠柱（含 NaN）。
     * footprint 必须一致。
     */
    public Heightfield merge(Heightfield other) {
        if (other.width == 0 || other.depth == 0) {
            return this;
        }
        if (width == 0 || depth == 0) {
            return other;
        }
        if (other.footprint != footprint) {
            throw new IllegalArgumentException(
                    "高度场合并 footprint 必须一致: " + footprint + " vs " + other.footprint);
        }
        int minX = Math.min(originX, other.originX);
        int minZ = Math.min(originZ, other.originZ);
        int maxX = Math.max(originX + width - 1, other.originX + other.width - 1);
        int maxZ = Math.max(originZ + depth - 1, other.originZ + other.depth - 1);
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        float[] nextY = new float[w * d];
        Arrays.fill(nextY, Float.NaN);
        int[] nextRgb = new int[w * d];
        copyInto(nextY, nextRgb, w, minX, minZ, this);
        copyInto(nextY, nextRgb, w, minX, minZ, other);
        float nextFloor = Float.MAX_VALUE;
        for (float y : nextY) {
            if (!Float.isNaN(y)) {
                nextFloor = Math.min(nextFloor, y);
            }
        }
        return new Heightfield(footprint, minX, minZ, w, d, nextY, nextRgb,
                nextFloor == Float.MAX_VALUE ? 0 : nextFloor);
    }

    private static void copyInto(float[] destY, int[] destRgb, int destWidth,
                                 int destOriginX, int destOriginZ, Heightfield src) {
        for (int z = 0; z < src.depth; z++) {
            for (int x = 0; x < src.width; x++) {
                int destIndex = (src.originZ + z - destOriginZ) * destWidth + (src.originX + x - destOriginX);
                int srcIndex = z * src.width + x;
                destY[destIndex] = src.topY[srcIndex];
                destRgb[destIndex] = src.rgb[srcIndex];
            }
        }
    }

    /** 聚合出上一层（footprint ×2）：柱顶取 2×2 最高，颜色取面积（出现次数）多数色。 */
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
        int[] colors = new int[4];
        int[] counts = new int[4];
        for (int pz = 0; pz < pDepth; pz++) {
            for (int px = 0; px < pWidth; px++) {
                int baseCx = (pOriginX + px) * 2;
                int baseCz = (pOriginZ + pz) * 2;
                float best = Float.NaN;
                int nColors = 0;
                for (int i = 0; i < 4; i++) {
                    counts[i] = 0;
                }
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        float childY = topY(baseCx + dx, baseCz + dz);
                        if (Float.isNaN(childY)) {
                            continue;
                        }
                        if (Float.isNaN(best) || childY > best) {
                            best = childY;
                        }
                        int childRgb = rgb(baseCx + dx, baseCz + dz);
                        boolean found = false;
                        for (int i = 0; i < nColors; i++) {
                            if (colors[i] == childRgb) {
                                counts[i]++;
                                found = true;
                                break;
                            }
                        }
                        if (!found && nColors < 4) {
                            colors[nColors] = childRgb;
                            counts[nColors] = 1;
                            nColors++;
                        }
                    }
                }
                int winner = 0;
                int bestCount = -1;
                for (int i = 0; i < nColors; i++) {
                    if (counts[i] > bestCount) {
                        bestCount = counts[i];
                        winner = colors[i];
                    }
                }
                int index = pz * pWidth + px;
                pTopY[index] = best;
                pRgb[index] = winner;
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

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public float[] copyTopY() {
        return topY.clone();
    }

    public int[] copyRgb() {
        return rgb.clone();
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
