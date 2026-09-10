package online.yudream.voxelith.tile.domain.atlas;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.Optional;

/**
 * 贴图像素来源端口：按贴图 id 取像素（实现位于 infrastructure，走资源目录 + 图片解码）。
 */
public interface TexturePixelSource {

    Optional<AtlasTexture> load(Identifier textureId);
}
