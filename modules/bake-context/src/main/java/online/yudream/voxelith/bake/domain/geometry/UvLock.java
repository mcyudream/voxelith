package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;

/**
 * 变体 uvlock 的 uv 重投影：1:1 复刻原版 1.20.4 的 FaceBakery.recomputeUVs +
 * BlockMath.getUVLockTransform（官方映射反编译对照，测试里有逐行转写的对拍实现）。
 * <p>
 * 原版语义：uvlock 时把 JSON 的 uv 矩形与 rotation 视为「未旋转姿态」，
 * 经 R = GL[dir]·S⁻¹·LG[newDir] 再做 blockCenterToCorner 三明治变换出新矩形与新 rotation；
 * 投影方向翻转（signum 变号）时交换 min/max 保持 u1≤u2、v1≤v2 的矩形约定；
 * 新 rotation 由 R 映射原 rotation 方向向量后按 90° 步进取整取负。
 * LG 表（面局部 → 世界基的右手矩阵）：SOUTH=I、EAST=rotY(+90)、WEST=rotY(-90)、
 * NORTH=rotY(180)、UP=rotX(-90)、DOWN=rotX(+90)；S = rotY(-y)·rotX(-x)（变体旋转，
 * 与 ModelRotation 的顶点变换同一约定）。
 */
final class UvLock {

    private UvLock() {
    }

    /**
     * @param rect 旋转前姿态的 uv 矩形 [u1,v1,u2,v2]（0~16，缺省已按元素跨度展开）
     * @param rot  JSON uv rotation（0/90/180/270）
     * @return uvlock 后的四角 uv [4][2]（索引制采样，见 FaceProjection.uvsFromRect）
     */
    static float[][] reproject(Direction dir, int variantX, int variantY, float[] rect, int rot) {
        float[] locked = recomputeRect(dir, variantX, variantY, rect, rot);
        return FaceProjection.uvsFromRect(locked, recomputeRotation(dir, variantX, variantY, rot));
    }

    /** 原版 recomputeUVs 前半：矩形角 (u1,v1)/(u2,v2) 经 uvlock 矩阵变换 + 符号修正 → 新矩形。 */
    private static float[] recomputeRect(Direction dir, int variantX, int variantY, float[] rect, int rot) {
        float[] r3 = uvLockMatrix(dir, variantX, variantY);
        float u1 = rect[0], v1 = rect[1], u2 = rect[2], v2 = rect[3];
        float[] t1 = transformUv(r3, u1, v1);
        float[] t2 = transformUv(r3, u2, v2);
        float u1n = t1[0], v1n = t1[1];
        float u2n = t2[0], v2n = t2[1];
        float minU, maxU, minV, maxV;
        if (Math.signum(u2 - u1) == Math.signum(u2n - u1n)) {
            minU = u1n;
            maxU = u2n;
        } else {
            minU = u2n;
            maxU = u1n;
        }
        if (Math.signum(v2 - v1) == Math.signum(v2n - v1n)) {
            minV = v1n;
            maxV = v2n;
        } else {
            minV = v2n;
            maxV = v1n;
        }
        return new float[]{minU, minV, maxU, maxV};
    }

    /** 原版 recomputeUVs 后半：rotation 方向向量 (cos, sin, 0) 经矩阵映射后按 90° 步进取整取负。 */
    private static int recomputeRotation(Direction dir, int variantX, int variantY, int rot) {
        float[] r3 = uvLockMatrix(dir, variantX, variantY);
        double rad = Math.toRadians(rot);
        float[] v = mulVec(r3, new float[]{(float) Math.cos(rad), (float) Math.sin(rad), 0});
        double degrees = Math.toDegrees(Math.atan2(v[1], v[0])) / 90.0;
        return Math.floorMod(-(int) Math.round(degrees) * 90, 360);
    }

    /** uvlock 矩阵（3x3 行主序）：GL[dir]·S⁻¹·LG[newDir]，newDir=变体旋转后的面方向。 */
    private static float[] uvLockMatrix(Direction dir, int variantX, int variantY) {
        float[] n = {dir.nx(), dir.ny(), dir.nz()};
        ModelRotation.rotateVariantVector(n, variantX, variantY);
        Direction newDir = ModelRotation.nearest(n[0], n[1], n[2]);
        float[] sInv = mul(inverseOfVariant(variantX, variantY), localToGlobal(newDir));
        return mul(globalToLocal(dir), sInv);
    }

    /** S = rotY(-y)·rotX(-x)（与 ModelRotation 顶点变换同约定）→ S⁻¹ = rotX(+x)·rotY(+y)。 */
    private static float[] inverseOfVariant(int variantX, int variantY) {
        return mul(rotX(norm(variantX)), rotY(norm(variantY)));
    }

    /** 面局部 → 世界基（原版 BlockMath VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL 表）。 */
    private static float[] localToGlobal(Direction dir) {
        return switch (dir) {
            case SOUTH -> identity();
            case EAST -> rotY(90);
            case WEST -> rotY(270);
            case NORTH -> rotY(180);
            case UP -> rotX(270);
            case DOWN -> rotX(90);
        };
    }

    /** 纯旋转矩阵的逆 = 转置（原版 GLOBAL_TO_LOCAL 表即 LG 的逆）。 */
    private static float[] globalToLocal(Direction dir) {
        return transpose(localToGlobal(dir));
    }

    /** uv 平面点 (u,v)（0~16，z=0）经矩阵映射：p → R·(p-(8,8,8)) + (8,8,8)，只取 (x,y)。 */
    private static float[] transformUv(float[] r3, float u, float v) {
        float[] p = {u - 8, v - 8, -8};
        float[] t = mulVec(r3, p);
        return new float[]{t[0] + 8, t[1] + 8};
    }

    private static int norm(int deg) {
        return ((deg % 360) + 360) % 360;
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    private static float[] transpose(float[] m) {
        return new float[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    /** 行主序 3x3 矩阵乘。 */
    private static float[] mul(float[] a, float[] b) {
        float[] out = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col];
            }
        }
        return out;
    }

    private static float[] mulVec(float[] m, float[] v) {
        return new float[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    /** 右手 rotY(+deg)，deg 为 90 倍数：(x,y,z)→(z,y,-x) 等。 */
    private static float[] rotY(int deg) {
        return switch (norm(deg)) {
            case 0 -> identity();
            case 90 -> new float[]{0, 0, 1, 0, 1, 0, -1, 0, 0};
            case 180 -> new float[]{-1, 0, 0, 0, 1, 0, 0, 0, -1};
            case 270 -> new float[]{0, 0, -1, 0, 1, 0, 1, 0, 0};
            default -> throw new IllegalArgumentException("仅支持 90 倍数: " + deg);
        };
    }

    /** 右手 rotX(+deg)：(x,y,z)→(x,-z,y) 等。 */
    private static float[] rotX(int deg) {
        return switch (norm(deg)) {
            case 0 -> identity();
            case 90 -> new float[]{1, 0, 0, 0, 0, -1, 0, 1, 0};
            case 180 -> new float[]{1, 0, 0, 0, -1, 0, 0, 0, -1};
            case 270 -> new float[]{1, 0, 0, 0, 0, 1, 0, -1, 0};
            default -> throw new IllegalArgumentException("仅支持 90 倍数: " + deg);
        };
    }
}
