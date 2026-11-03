package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.sharedkernel.vo.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引制 uv 采样表：与原版 FaceInfo（角 i）+ BlockFaceUV.getShiftedIndex（(i+rot/90)%4）
 * 的期望值逐项对拍。原版 FaceInfo 角 i 与本表角 (i+1)%4 一一对应。
 */
class FaceProjectionTest {

    private static final float[] FULL = {0, 0, 16, 16};
    private static final float[] SUB = {3, 5, 13, 12};

    @Test
    void fullRectRotation0MatchesVanillaCornerTable() {
        // rot=0 时各角期望（FaceInfo 角 i 取 (u1,v1),(u1,v2),(u2,v2),(u2,v1)）：
        // 本表 r = FaceInfo (r+1)%4 → r0=(u1,v2), r1=(u2,v2), r2=(u2,v1), r3=(u1,v1)
        float[][] uvs = FaceProjection.uvsFromRect(FULL, 0);
        assertThat(uvs[0]).containsExactly(0f, 16f);
        assertThat(uvs[1]).containsExactly(16f, 16f);
        assertThat(uvs[2]).containsExactly(16f, 0f);
        assertThat(uvs[3]).containsExactly(0f, 0f);
    }

    @Test
    void fullRectRotation90ShiftsCornersLikeVanilla() {
        // rot=90：FaceInfo 角 i 取矩形角 (i+1)%4 → r0=(u2,v2), r1=(u2,v1), r2=(u1,v1), r3=(u1,v2)
        float[][] uvs = FaceProjection.uvsFromRect(FULL, 90);
        assertThat(uvs[0]).containsExactly(16f, 16f);
        assertThat(uvs[1]).containsExactly(16f, 0f);
        assertThat(uvs[2]).containsExactly(0f, 0f);
        assertThat(uvs[3]).containsExactly(0f, 16f);
    }

    @Test
    void fullRectRotation180And270MatchVanilla() {
        // rot=180：r0=(u2,v1), r1=(u1,v1), r2=(u1,v2), r3=(u2,v2)
        float[][] uvs180 = FaceProjection.uvsFromRect(FULL, 180);
        assertThat(uvs180[0]).containsExactly(16f, 0f);
        assertThat(uvs180[1]).containsExactly(0f, 0f);
        assertThat(uvs180[2]).containsExactly(0f, 16f);
        assertThat(uvs180[3]).containsExactly(16f, 16f);
        // rot=270：r0=(u1,v1), r1=(u1,v2), r2=(u2,v2), r3=(u2,v1)
        float[][] uvs270 = FaceProjection.uvsFromRect(FULL, 270);
        assertThat(uvs270[0]).containsExactly(0f, 0f);
        assertThat(uvs270[1]).containsExactly(0f, 16f);
        assertThat(uvs270[2]).containsExactly(16f, 16f);
        assertThat(uvs270[3]).containsExactly(16f, 0f);
    }

    @Test
    void subRectIsCornerRemappedNotCoordinateRotated() {
        // 子矩形 [3,5,13,12] rot=90：角点仍取矩形自身的角（索引制），
        // 而不是把坐标绕 (8,8) 旋转（后者会落到矩形外的 (11,-3) 等）。
        float[][] uvs = FaceProjection.uvsFromRect(SUB, 90);
        assertThat(uvs[0]).containsExactly(13f, 12f);
        assertThat(uvs[1]).containsExactly(13f, 5f);
        assertThat(uvs[2]).containsExactly(3f, 5f);
        assertThat(uvs[3]).containsExactly(3f, 12f);
    }

    @Test
    void defaultRectExpandsElementSpanPerFaceAxis() {
        // down 面 u=x、v=16-z：元素 z∈[4,12] → rect=[0,4,16,12]
        float[] rect = FaceProjection.rawRect(Direction.DOWN, new float[]{0, 0, 4}, new float[]{16, 16, 12}, null);
        assertThat(rect).containsExactly(0f, 4f, 16f, 12f);
        // 显式 uv 原样返回
        float[] explicit = FaceProjection.rawRect(Direction.DOWN, new float[]{0, 0, 4}, new float[]{16, 16, 12}, new float[]{1, 2, 3, 4});
        assertThat(explicit).containsExactly(1f, 2f, 3f, 4f);
    }
}
