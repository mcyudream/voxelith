package online.yudream.voxelith.lod.domain.heightfield;

import java.util.ArrayList;
import java.util.List;

/**
 * 柱状高度场 → LOD 瓦片几何：每瓦片 32×32 柱，顶面按行游程合并（同高同色），
 * 高度不连续处生成垂直裙边（沿边游程合并，同色同顶同底）。
 *
 * 裙边归属：一条边只由"较高柱所在瓦片"发射一次 —— 相邻瓦片对同一条边的判定结果互补，
 * 不会重复也不会遗漏；地图边界外视为下探到全场最低点 floorY 的空柱，形成边缘围墙。
 * 方向明暗烘入颜色：顶面 1.0、东西向（x 面）0.6、南北向（z 面）0.8。
 */
public final class HeightfieldLodMesher {

    /** 每瓦片每边柱数（与 hires 瓦片 32 方块对齐：层级 L 瓦片 = 32 柱 × 2^L 方块）。 */
    public static final int COLUMNS_PER_TILE = 32;

    private static final float EPSILON = 1e-4f;
    private static final double SHADE_X = 0.6;
    private static final double SHADE_Z = 0.8;

    private static final float[] NORMAL_UP = {0, 1, 0};
    private static final float[] NORMAL_EAST = {1, 0, 0};
    private static final float[] NORMAL_WEST = {-1, 0, 0};
    private static final float[] NORMAL_SOUTH = {0, 0, 1};
    private static final float[] NORMAL_NORTH = {0, 0, -1};

    public List<LodQuad> meshTile(Heightfield field, int tileX, int tileZ) {
        List<LodQuad> quads = new ArrayList<>();
        meshTops(field, tileX, tileZ, quads);
        meshXSkirts(field, tileX, tileZ, quads);
        meshZSkirts(field, tileX, tileZ, quads);
        return quads;
    }

    /** 顶面：逐行（z 向）沿 x 游程合并同高同色柱。 */
    private void meshTops(Heightfield field, int tileX, int tileZ, List<LodQuad> quads) {
        int f = field.footprint();
        int cx0 = tileX * COLUMNS_PER_TILE;
        int cz0 = tileZ * COLUMNS_PER_TILE;
        for (int j = 0; j < COLUMNS_PER_TILE; j++) {
            int cz = cz0 + j;
            int i = 0;
            while (i < COLUMNS_PER_TILE) {
                float y = field.topY(cx0 + i, cz);
                if (Float.isNaN(y)) {
                    i++;
                    continue;
                }
                int rgb = field.rgb(cx0 + i, cz);
                int end = i;
                while (end + 1 < COLUMNS_PER_TILE
                        && field.topY(cx0 + end + 1, cz) == y
                        && field.rgb(cx0 + end + 1, cz) == rgb) {
                    end++;
                }
                float x0 = (cx0 + i) * (float) f;
                float x1 = (cx0 + end + 1) * (float) f;
                float z0 = cz * (float) f;
                float z1 = z0 + f;
                quads.add(new LodQuad(new float[]{
                        x0, y, z1, x1, y, z1, x1, y, z0, x0, y, z0,
                }, NORMAL_UP, rgb));
                i = end + 1;
            }
        }
    }

    /** x 向裙边：柱 (cx-1,cz) 与 (cx,cz) 之间的竖直边，平面 x = cx*f，沿 z 游程合并。 */
    private void meshXSkirts(Heightfield field, int tileX, int tileZ, List<LodQuad> quads) {
        int f = field.footprint();
        int cx0 = tileX * COLUMNS_PER_TILE;
        int cz0 = tileZ * COLUMNS_PER_TILE;
        for (int cx = cx0; cx <= cx0 + COLUMNS_PER_TILE; cx++) {
            int j = 0;
            while (j < COLUMNS_PER_TILE) {
                EdgeParams edge = xEdge(field, cx, cz0 + j, cx0);
                if (edge == null) {
                    j++;
                    continue;
                }
                int end = j;
                while (end + 1 < COLUMNS_PER_TILE) {
                    EdgeParams next = xEdge(field, cx, cz0 + end + 1, cx0);
                    if (next == null || !next.sameRun(edge)) {
                        break;
                    }
                    end++;
                }
                float x = cx * (float) f;
                float z0 = (cz0 + j) * (float) f;
                float z1 = (cz0 + end + 1) * (float) f;
                float y0 = edge.bottom();
                float y1 = edge.top();
                if (edge.facingEast()) {
                    quads.add(new LodQuad(new float[]{
                            x, y0, z1, x, y0, z0, x, y1, z0, x, y1, z1,
                    }, NORMAL_EAST, edge.rgb()));
                } else {
                    quads.add(new LodQuad(new float[]{
                            x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0,
                    }, NORMAL_WEST, edge.rgb()));
                }
                j = end + 1;
            }
        }
    }

    /** z 向裙边：柱 (cx,cz-1) 与 (cx,cz) 之间的竖直边，平面 z = cz*f，沿 x 游程合并。 */
    private void meshZSkirts(Heightfield field, int tileX, int tileZ, List<LodQuad> quads) {
        int f = field.footprint();
        int cx0 = tileX * COLUMNS_PER_TILE;
        int cz0 = tileZ * COLUMNS_PER_TILE;
        for (int cz = cz0; cz <= cz0 + COLUMNS_PER_TILE; cz++) {
            int i = 0;
            while (i < COLUMNS_PER_TILE) {
                EdgeParams edge = zEdge(field, cx0 + i, cz, cz0);
                if (edge == null) {
                    i++;
                    continue;
                }
                int end = i;
                while (end + 1 < COLUMNS_PER_TILE) {
                    EdgeParams next = zEdge(field, cx0 + end + 1, cz, cz0);
                    if (next == null || !next.sameRun(edge)) {
                        break;
                    }
                    end++;
                }
                float z = cz * (float) f;
                float x0 = (cx0 + i) * (float) f;
                float x1 = (cx0 + end + 1) * (float) f;
                float y0 = edge.bottom();
                float y1 = edge.top();
                if (edge.facingEast()) {
                    // 复用 facingEast 标志表示"北柱更高 → 朝南面"
                    quads.add(new LodQuad(new float[]{
                            x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z,
                    }, NORMAL_SOUTH, edge.rgb()));
                } else {
                    quads.add(new LodQuad(new float[]{
                            x1, y0, z, x0, y0, z, x0, y1, z, x1, y1, z,
                    }, NORMAL_NORTH, edge.rgb()));
                }
                i = end + 1;
            }
        }
    }

    /**
     * 评估 x 向边（左柱 cx-1 | 右柱 cx）。较高侧发射且较高柱在本瓦片内时返回参数，否则 null。
     */
    private EdgeParams xEdge(Heightfield field, int cx, int cz, int cx0) {
        float leftRaw = field.topY(cx - 1, cz);
        float rightRaw = field.topY(cx, cz);
        float left = Float.isNaN(leftRaw) ? field.floorY() : leftRaw;
        float right = Float.isNaN(rightRaw) ? field.floorY() : rightRaw;
        if (left > right + EPSILON) {
            // 左柱更高 → 左柱的东面；归属左柱所在瓦片
            if (cx - 1 < cx0 || cx - 1 >= cx0 + COLUMNS_PER_TILE) {
                return null;
            }
            return new EdgeParams(left, right, shade(field.rgb(cx - 1, cz), SHADE_X), true);
        }
        if (right > left + EPSILON) {
            if (cx < cx0 || cx >= cx0 + COLUMNS_PER_TILE) {
                return null;
            }
            return new EdgeParams(right, left, shade(field.rgb(cx, cz), SHADE_X), false);
        }
        return null;
    }

    /**
     * 评估 z 向边（北柱 cz-1 | 南柱 cz）。facingEast 标志复用为"北柱更高 → 朝南面"。
     */
    private EdgeParams zEdge(Heightfield field, int cx, int cz, int cz0) {
        float northRaw = field.topY(cx, cz - 1);
        float southRaw = field.topY(cx, cz);
        float north = Float.isNaN(northRaw) ? field.floorY() : northRaw;
        float south = Float.isNaN(southRaw) ? field.floorY() : southRaw;
        if (north > south + EPSILON) {
            if (cz - 1 < cz0 || cz - 1 >= cz0 + COLUMNS_PER_TILE) {
                return null;
            }
            return new EdgeParams(north, south, shade(field.rgb(cx, cz - 1), SHADE_Z), true);
        }
        if (south > north + EPSILON) {
            if (cz < cz0 || cz >= cz0 + COLUMNS_PER_TILE) {
                return null;
            }
            return new EdgeParams(south, north, shade(field.rgb(cx, cz), SHADE_Z), false);
        }
        return null;
    }

    private static int shade(int rgb, double factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * 一条可发射裙边边的参数。
     *
     * @param facingEast x 向边：true = 左柱高（朝东面）；z 向边：true = 北柱高（朝南面）
     */
    private record EdgeParams(float top, float bottom, int rgb, boolean facingEast) {
        boolean sameRun(EdgeParams other) {
            return top == other.top && bottom == other.bottom
                    && rgb == other.rgb && facingEast == other.facingEast;
        }
    }
}
