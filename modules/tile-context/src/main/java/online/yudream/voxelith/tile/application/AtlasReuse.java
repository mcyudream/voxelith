package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;

/**
 * 增量重跑时复用已发布图集：layout 决定 UV 映射，png 直接写入新 glb，
 * 避免现场重打包打乱单元格序号导致旧瓦片 UV 错位。
 */
public record AtlasReuse(AtlasLayout layout, byte[] png) {
}
