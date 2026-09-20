package online.yudream.voxelith.bake.application.dto;

/**
 * 跨上下文传递的烘焙四边形（世界坐标，1 方块 = 1）。与 domain 的 BakedQuad 字段一致，
 * 作为 application 层契约供 tile 等下游上下文消费。
 */
public record BakedQuadData(float[] positions, float[] uvs, float[] normal,
                            String texture, int tintIndex, boolean shade, String face,
                            int tintRgb, byte[] skyLight, byte[] blockLight, byte[] ao,
                            boolean translucent) {

    /**
     * 「非方块面」的 {@code face} 标记：实体几何（盔甲架等）用它区别于方块的六个朝向。
     *
     * <p>为什么需要区分：LOD 的地表高度场/航拍色是按「朝上且投影面积够大」的面采样出来的，
     * 而盔甲架的底座顶面正好落进这个判据——不排除的话，实体就成了地表的一部分
     * （高度场被抬到实体的 y、航拍色变成木纹），空中装饰用的盔甲架还会在 LOD 里拉出细柱。
     * 原版的高度场同样只统计方块，因此实体几何一律不进地表采样。</p>
     */
    public static final String NON_TERRAIN_FACE = "entity";
}
