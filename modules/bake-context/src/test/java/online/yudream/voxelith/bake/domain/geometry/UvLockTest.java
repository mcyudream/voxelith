package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * UvLock 与原版 1.20.4 反编译逻辑（FaceBakery.recomputeUVs + BlockMath.getUVLockTransform）
 * 的全组合对拍：oracle 用 4x4 带平移矩阵逐行转写原版实现（与被测实现的 3x3 手工展开互相独立），
 * 覆盖 6 方向 × x/y 各 {0,90,180,270} × rotation {0,90,180,270} × 4 种 uv 矩形。
 */
class UvLockTest {

    private static final Direction[] DIRS = Direction.values();
    private static final int[] QUARTERS = {0, 90, 180, 270};
    private static final float[][] RECTS = {
            {0, 0, 16, 16},      // 满跨度（楼梯等缺省 uv）
            {3, 5, 13, 12},      // 子矩形（显式 uv）
            {4, 4, 12, 12},      // 正方形子矩形
            {6, 2, 9, 14},       // 细长矩形
    };

    @Test
    void matchesDecompiledVanillaAcrossAllCombinations() {
        for (Direction dir : DIRS) {
            for (int x : QUARTERS) {
                for (int y : QUARTERS) {
                    for (int rot : QUARTERS) {
                        for (float[] rect : RECTS) {
                            float[][] expected = oracle(dir, x, y, rect, rot);
                            float[][] actual = UvLock.reproject(dir, x, y, rect, rot);
                            for (int i = 0; i < 4; i++) {
                                String caseInfo = "dir=%s x=%d y=%d rot=%d rect=%s corner=%d"
                                        .formatted(dir, x, y, rot, java.util.Arrays.toString(rect), i);
                                assertThat((double) actual[i][0]).as(caseInfo + " u").isCloseTo(expected[i][0], within(1e-4));
                                assertThat((double) actual[i][1]).as(caseInfo + " v").isCloseTo(expected[i][1], within(1e-4));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void identityVariantKeepsRectAndRemapsRotationLikeVanilla() {
        // x=y=0 时矩阵为 I：矩形原样；rotation 按 atan2 映射 0→0、90→270、180→180、270→90
        //（uvlock 把 rotation 解释为「重投影后姿态」的语义，非恒等映射，与反编译逐行一致）。
        float[] rect = {2, 3, 9, 11};
        assertThat(UvLock.reproject(Direction.NORTH, 0, 0, rect, 0)).isDeepEqualTo(FaceProjection.uvsFromRect(rect, 0));
        assertThat(UvLock.reproject(Direction.NORTH, 0, 0, rect, 180)).isDeepEqualTo(FaceProjection.uvsFromRect(rect, 180));
        assertThat(UvLock.reproject(Direction.NORTH, 0, 0, rect, 90)).isDeepEqualTo(FaceProjection.uvsFromRect(rect, 270));
        assertThat(UvLock.reproject(Direction.NORTH, 0, 0, rect, 270)).isDeepEqualTo(FaceProjection.uvsFromRect(rect, 90));
    }

    // ---------- oracle：原版反编译逻辑的逐行转写（4x4 带平移） ----------

    private static float[][] oracle(Direction dir, int variantX, int variantY, float[] rect, int rot) {
        float[] s = mul4(rotY4((float) Math.toRadians(-variantY)), rotX4((float) Math.toRadians(-variantX)));
        float[] sInv = transpose4(s);
        Direction newDir = nearest(mulVec4(s, dir.nx(), dir.ny(), dir.nz()));
        // BlockMath.getUVLockTransform：GL[dir]·S⁻¹·LG[newDir]，再 blockCenterToCorner
        float[] core = mul4(globalToLocal(dir), mul4(sInv, localToGlobal(newDir)));
        float[] m = mul4(translation(0.5f, 0.5f, 0.5f), mul4(core, translation(-0.5f, -0.5f, -0.5f)));
        // FaceBakery.recomputeUVs：变换 (u/16,v/16,0,1) 两角，只取 (x,y)×16
        float u1 = rect[0], v1 = rect[1], u2 = rect[2], v2 = rect[3];
        float[] q1 = mulVec4(m, u1 / 16f, v1 / 16f, 0f);
        float[] q2 = mulVec4(m, u2 / 16f, v2 / 16f, 0f);
        float u1n = 16 * q1[0], v1n = 16 * q1[1];
        float u2n = 16 * q2[0], v2n = 16 * q2[1];
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
        // rotation：原版取 new Matrix3f(m)（只取旋转部分，不含中心平移）映射 (cos r, sin r, 0) 后按 90° 步进取整取负
        double rad = Math.toRadians(rot);
        float[] v = mulDir4(core, (float) Math.cos(rad), (float) Math.sin(rad), 0f);
        int newRot = Math.floorMod(-((int) Math.round(Math.toDegrees(Math.atan2(v[1], v[0])) / 90.0)) * 90, 360);
        // 采样：原版 FaceInfo 角 i 取 (i+newRot/90)%4；本表角 r = FaceInfo (r+1)%4
        float[][] out = new float[4][2];
        for (int r = 0; r < 4; r++) {
            int idx = (r + 1 + newRot / 90) % 4;
            out[r][0] = idx < 2 ? minU : maxU;
            out[r][1] = idx == 0 || idx == 3 ? minV : maxV;
        }
        return out;
    }

    private static Direction nearest(float[] v) {
        float ax = Math.abs(v[0]), ay = Math.abs(v[1]), az = Math.abs(v[2]);
        if (ax >= ay && ax >= az) {
            return v[0] >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az) {
            return v[1] >= 0 ? Direction.UP : Direction.DOWN;
        }
        return v[2] >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static float[] localToGlobal(Direction dir) {
        return switch (dir) {
            case SOUTH -> identity4();
            case EAST -> rotY4((float) (Math.PI / 2));
            case WEST -> rotY4((float) (-Math.PI / 2));
            case NORTH -> rotY4((float) Math.PI);
            case UP -> rotX4((float) (-Math.PI / 2));
            case DOWN -> rotX4((float) (Math.PI / 2));
        };
    }

    private static float[] globalToLocal(Direction dir) {
        return transpose4(localToGlobal(dir));
    }

    private static float[] identity4() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] translation(float x, float y, float z) {
        return new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
    }

    private static float[] rotX4(float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    private static float[] rotY4(float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    private static float[] transpose4(float[] m) {
        return new float[]{
                m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]};
    }

    private static float[] mul4(float[] a, float[] b) {
        float[] out = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[row * 4 + k] * b[k * 4 + col];
                }
                out[row * 4 + col] = sum;
            }
        }
        return out;
    }

    /** 齐次变换作用于方向/点（平移含入，纯旋转+平移下 w=1）。 */
    private static float[] mulVec4(float[] m, float x, float y, float z) {
        return new float[]{
                m[0] * x + m[1] * y + m[2] * z + m[3],
                m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    /** 仅旋转部分（不含平移列），对应原版 new Matrix3f(Matrix4f) 的语义。 */
    private static float[] mulDir4(float[] m, float x, float y, float z) {
        return new float[]{
                m[0] * x + m[1] * y + m[2] * z,
                m[4] * x + m[5] * y + m[6] * z,
                m[8] * x + m[9] * y + m[10] * z};
    }
}
