package online.yudream.voxelith.tile.infrastructure.image;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * 从资源目录读贴图字节并用 ImageIO 解码；动画贴图（竖条）裁第一帧（宽×宽）。
 */
public final class CatalogTexturePixelSource implements TexturePixelSource {

    private final ResolvedResourceCatalog catalog;

    public CatalogTexturePixelSource(ResolvedResourceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<AtlasTexture> load(Identifier textureId) {
        return catalog.texture(textureId).map(bytes -> decode(textureId, bytes));
    }

    private static AtlasTexture decode(Identifier id, byte[] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("贴图解码失败: " + id, e);
        }
        if (image == null) {
            throw new IllegalArgumentException("无法识别的贴图格式: " + id);
        }
        int frameHeight = Math.min(image.getHeight(), image.getWidth());
        int width = image.getWidth();
        int[] argb = image.getRGB(0, 0, width, frameHeight, null, 0, width);
        return new AtlasTexture(id, width, frameHeight, argb);
    }
}
