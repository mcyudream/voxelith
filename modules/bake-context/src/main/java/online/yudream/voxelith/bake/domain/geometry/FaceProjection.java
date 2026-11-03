package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;

/**
 * 面投影表：每个方向的规范四角（外向逆时针）与 uv 坐标轴映射。
 * <p>
 * 语义与原版对齐（1.20.4 官方映射反编译 FaceInfo/BlockFaceUV/FaceBakery）：
 * uv 矩形缺省时取元素在面两轴上的跨度（等价 BlockFaceUV.setMissingUv）；
 * 四角到矩形角点的映射是<b>索引制</b>而非坐标插值——原版 FaceInfo 角 i 的 uv 取
 * rect 角 (i + rotation/90) % 4，且 FaceInfo 角 i 与本表角 (i+1)%4 一一对应（六面一致右移一位），
 * 即本表角 r 取 rect 角 (r + 1 + rotation/90) % 4。
 * 注意原版的 uv rotation 是矩形角点重排，不是坐标绕 (8,8) 旋转（对子矩形两者不等价）。
 * uv 坐标轴（贴合原版默认 uv 表）：
 * down  u=x, v=16-z；up u=x, v=z；north u=16-x, v=16-y；
 * south u=x, v=16-y；west u=z, v=16-y；east u=16-z, v=16-y。
 */
public final class FaceProjection {

    private FaceProjection() {
    }

    /** 规范四角（外向逆时针），返回 [4][3]。 */
    public static float[][] corners(Direction dir, float[] from, float[] to) {
        float x1 = from[0], y1 = from[1], z1 = from[2];
        float x2 = to[0], y2 = to[1], z2 = to[2];
        return switch (dir) {
            case DOWN -> new float[][]{{x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}, {x1, y1, z2}};
            case UP -> new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}, {x1, y2, z1}};
            case NORTH -> new float[][]{{x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}, {x2, y2, z1}};
            case SOUTH -> new float[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}, {x1, y2, z2}};
            case WEST -> new float[][]{{x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}, {x1, y2, z1}};
            case EAST -> new float[][]{{x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}, {x2, y2, z2}};
        };
    }

    /**
     * 计算四角 uv，返回 [4][2]（0~16 坐标）。
     *
     * @param uv       显式 [u1,v1,u2,v2]，null = 按元素跨度推导
     * @param rotation uv 旋转（0/90/180/270，原版语义=矩形角点重排）
     */
    public static float[][] uvs(Direction dir, float[] from, float[] to, float[] uv, int rotation) {
        return uvsFromRect(rawRect(dir, from, to, uv), rotation);
    }

    /** uv 矩形 [u1,v1,u2,v2]：显式 uv 优先，缺省取元素在面两轴上的跨度（setMissingUv 语义）。 */
    public static float[] rawRect(Direction dir, float[] from, float[] to, float[] uv) {
        float uMin = uCoord(dir, from);
        float uMax = uCoord(dir, to);
        float vMin = vCoord(dir, from);
        float vMax = vCoord(dir, to);
        if (uMin > uMax) {
            float t = uMin; uMin = uMax; uMax = t;
        }
        if (vMin > vMax) {
            float t = vMin; vMin = vMax; vMax = t;
        }
        return uv != null
                ? new float[]{uv[0], uv[1], uv[2], uv[3]}
                : new float[]{uMin, vMin, uMax, vMax};
    }

    /** 索引制采样：本表角 r 的 uv = rect 角 (r + 1 + rotation/90) % 4（FaceInfo+getShiftedIndex 语义）。 */
    public static float[][] uvsFromRect(float[] rect, int rotation) {
        int shift = ((rotation % 360) + 360) % 360 / 90;
        float[][] result = new float[4][2];
        for (int r = 0; r < 4; r++) {
            int idx = (r + 1 + shift) % 4;
            float u = idx < 2 ? rect[0] : rect[2];
            float v = idx == 0 || idx == 3 ? rect[1] : rect[3];
            result[r] = new float[]{u, v};
        }
        return result;
    }

    private static float uCoord(Direction dir, float[] p) {
        return switch (dir) {
            case DOWN, UP, SOUTH -> p[0];
            case NORTH -> 16 - p[0];
            case WEST -> p[2];
            case EAST -> 16 - p[2];
        };
    }

    private static float vCoord(Direction dir, float[] p) {
        return switch (dir) {
            case UP -> p[2];
            case DOWN -> 16 - p[2];
            default -> 16 - p[1];
        };
    }
}
