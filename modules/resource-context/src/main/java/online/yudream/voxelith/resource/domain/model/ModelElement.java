package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Direction;

import java.util.List;
import java.util.Map;

/**
 * 模型元素：一个长方体（0~16 坐标系）+ 各方向面。
 */
public record ModelElement(
        float[] from,
        float[] to,
        ElementRotation rotation,
        boolean shade,
        Map<Direction, ElementFace> faces) {

    /** 顶点着色按 MC 惯例：shade 缺失时视为 true。 */
    public static final boolean DEFAULT_SHADE = true;
}
