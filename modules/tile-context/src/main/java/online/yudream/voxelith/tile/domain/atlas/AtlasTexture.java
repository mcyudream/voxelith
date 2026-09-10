package online.yudream.voxelith.tile.domain.atlas;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

/**
 * 待入图集的贴图像素（argb，行主序）。动画贴图（高 > 宽的竖条）只取第一帧。
 *
 * @param cellWidth  有效帧宽
 * @param cellHeight 有效帧高
 */
public record AtlasTexture(Identifier id, int cellWidth, int cellHeight, int[] argb) {
}
