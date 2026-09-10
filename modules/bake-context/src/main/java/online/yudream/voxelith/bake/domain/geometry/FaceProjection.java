package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;

/**
 * 面投影表：每个方向的规范四角（外向逆时针）与 uv 坐标轴映射。
 * <p>
 * uv 推导与原版 FaceBakery 语义一致：uv 缺省时取元素在面两轴上的跨度
 * （等价于 [x1,z1,x2,z2] 式默认值表）；显式 uv 时按角点在跨度中的比例插值。
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
     * @param rotation uv 旋转（0/90/180/270，绕 (8,8) 顺时针）
     */
    public static float[][] uvs(Direction dir, float[] from, float[] to, float[] uv, int rotation) {
        float[][] corners = corners(dir, from, to);
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
        float u1 = uv != null ? uv[0] : uMin;
        float v1 = uv != null ? uv[1] : vMin;
        float u2 = uv != null ? uv[2] : uMax;
        float v2 = uv != null ? uv[3] : vMax;

        float[][] result = new float[4][2];
        for (int i = 0; i < 4; i++) {
            float fu = uMax == uMin ? 0 : (uCoord(dir, corners[i]) - uMin) / (uMax - uMin);
            float fv = vMax == vMin ? 0 : (vCoord(dir, corners[i]) - vMin) / (vMax - vMin);
            float u = u1 + fu * (u2 - u1);
            float v = v1 + fv * (v2 - v1);
            result[i] = rotateUv(u, v, rotation);
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

    private static float[] rotateUv(float u, float v, int rotation) {
        return switch (((rotation % 360) + 360) % 360) {
            case 90 -> new float[]{16 - v, u};
            case 180 -> new float[]{16 - u, 16 - v};
            case 270 -> new float[]{v, 16 - u};
            default -> new float[]{u, v};
        };
    }
}
