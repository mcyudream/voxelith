package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;

/**
 * 旋转数学：全部遵循 MC 约定 —— 绕 +轴的右手旋转、角度取负。
 * rotX(90°): (x,y,z)→(x,z,-y)；rotY(90°): (x,y,z)→(-z,y,x)。已用楼梯/末地烛验证。
 */
public final class ModelRotation {

    private ModelRotation() {
    }

    /**
     * 元素旋转：绕 pivot、按轴旋转 angle（MC 限制 ±22.5/±45），rescale 时对垂直于轴的两个分量
     * 施加 1/cos(angle) 放大（MC 对 22.5/45 度元素的补偿）。
     */
    public static void rotateElement(float[] p, float[] pivot, String axis, float angleDeg, boolean rescale) {
        double rad = Math.toRadians(-angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double scale = rescale && angleDeg % 90 != 0 ? 1.0 / cos : 1.0;

        double x = p[0] - pivot[0];
        double y = p[1] - pivot[1];
        double z = p[2] - pivot[2];
        switch (axis.toUpperCase()) {
            case "X" -> {
                double y1 = y * cos - z * sin;
                double z1 = y * sin + z * cos;
                p[1] = (float) (pivot[1] + y1 * scale);
                p[2] = (float) (pivot[2] + z1 * scale);
            }
            case "Y" -> {
                double x1 = x * cos + z * sin;
                double z1 = -x * sin + z * cos;
                p[0] = (float) (pivot[0] + x1 * scale);
                p[2] = (float) (pivot[2] + z1 * scale);
            }
            case "Z" -> {
                double x1 = x * cos - y * sin;
                double y1 = x * sin + y * cos;
                p[0] = (float) (pivot[0] + x1 * scale);
                p[1] = (float) (pivot[1] + y1 * scale);
            }
            default -> throw new IllegalArgumentException("未知旋转轴: " + axis);
        }
    }

    /**
     * 变体旋转：绕方块中心 (8,8,8)，先 x 后 y，角度为 90 的整数倍。
     * 90 倍数用精确整数运算，避免浮点漂移破坏网格拼接。
     */
    public static void rotateVariant(float[] p, int xDeg, int yDeg) {
        rotateXExact(p, norm(xDeg));
        rotateYExact(p, norm(yDeg));
    }

    /** 法向/方向向量同样旋转（以原点为 pivot 的方向向量版本）。 */
    public static void rotateVariantVector(float[] v, int xDeg, int yDeg) {
        float[] p = {v[0] + 8, v[1] + 8, v[2] + 8};
        rotateVariant(p, xDeg, yDeg);
        v[0] = p[0] - 8;
        v[1] = p[1] - 8;
        v[2] = p[2] - 8;
    }

    /** 元素旋转作用于方向向量。 */
    public static void rotateElementVector(float[] v, String axis, float angleDeg) {
        float[] pivot = {0, 0, 0};
        rotateElement(v, pivot, axis, angleDeg, false);
    }

    /** 把任意向量吸附到最近的轴向方向。 */
    public static Direction nearest(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax >= ay && ax >= az) {
            return nx >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az) {
            return ny >= 0 ? Direction.UP : Direction.DOWN;
        }
        return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int norm(int deg) {
        return ((deg % 360) + 360) % 360;
    }

    private static void rotateXExact(float[] p, int deg) {
        if (deg == 0) {
            return;
        }
        float y = p[1] - 8, z = p[2] - 8;
        switch (deg) {
            case 90 -> { p[1] = 8 + z; p[2] = 8 - y; }
            case 180 -> { p[1] = 8 - y; p[2] = 8 - z; }
            case 270 -> { p[1] = 8 - z; p[2] = 8 + y; }
            default -> throw new IllegalArgumentException("变体旋转仅支持 90 倍数: " + deg);
        }
    }

    private static void rotateYExact(float[] p, int deg) {
        if (deg == 0) {
            return;
        }
        float x = p[0] - 8, z = p[2] - 8;
        switch (deg) {
            case 90 -> { p[0] = 8 - z; p[2] = 8 + x; }
            case 180 -> { p[0] = 8 - x; p[2] = 8 - z; }
            case 270 -> { p[0] = 8 + z; p[2] = 8 - x; }
            default -> throw new IllegalArgumentException("变体旋转仅支持 90 倍数: " + deg);
        }
    }
}
