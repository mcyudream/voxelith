package online.yudream.voxelith.lod.domain.heightfield;

/**
 * 柱表面采样点：一个朝上表面的质心位置与最终颜色（贴图均色 × 生物群系染色，未含方向明暗）。
 * 高度场只关心从上往下看得见的表面，故全部采样均为朝上面。
 *
 * @param x/z 世界坐标质心
 * @param y   表面高度（顶点最大 y）
 * @param rgb 颜色 0xRRGGBB
 */
public record LodSample(float x, float y, float z, int rgb) {
}
