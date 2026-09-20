package online.yudream.voxelith.tile.infrastructure.image;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.TextureAliases;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 从资源目录读贴图字节并用 ImageIO 解码；动画贴图（竖条）裁第一帧（宽×宽）。
 *
 * <p>解码结果按贴图 id 缓存：LOD 采样对每个朝上面调用 {@code sampleUvAverageRgb}，
 * 全图数千万次调用若逐次走 jar 查找 + PNG 解码（含 ImageIO SPI 探测与文件缓存写盘）
 * 会让 LOD 链路假死数小时；缓存后仅首次解码，后续为纯内存查表。</p>
 *
 * <p><b>跨版本贴图改名兜底</b>：原名查不到时按 {@link TextureAliases} 的候选表再试
 * （1.20.3 的 {@code grass→short_grass}、1.17 的 {@code grass_path→dirt_path}、
 * 1.13 flattening 的羊毛/陶瓦/木板改名等）。命中就顶替使用并记进
 * {@link #substitutions()}，由调用方报给用户——画面不再碎成品红，同时版本不一致仍能被发现。</p>
 */
public final class CatalogTexturePixelSource implements TexturePixelSource {

    private final ResolvedResourceCatalog catalog;
    private final ConcurrentMap<Identifier, Optional<AtlasTexture>> cache = new ConcurrentHashMap<>();
    /** 被别名顶替的贴图：原 id → 实际使用的 id（诊断输出用）。 */
    private final ConcurrentMap<String, String> substitutions = new ConcurrentHashMap<>();

    public CatalogTexturePixelSource(ResolvedResourceCatalog catalog) {
        this.catalog = catalog;
        // 解码源是内存中的字节数组，无需 ImageIO 落盘文件缓存（默认 useCache=true 会写临时文件）
        ImageIO.setUseCache(false);
    }

    @Override
    public Optional<AtlasTexture> load(Identifier textureId) {
        return cache.computeIfAbsent(textureId, this::decodeFromCatalog);
    }

    private Optional<AtlasTexture> decodeFromCatalog(Identifier textureId) {
        Optional<byte[]> direct = catalog.texture(textureId);
        if (direct.isPresent()) {
            return direct.map(bytes -> decode(textureId, bytes));
        }
        for (String candidate : TextureAliases.candidates(textureId.toString())) {
            Optional<byte[]> aliased = catalog.texture(Identifier.parse(candidate));
            if (aliased.isPresent()) {
                substitutions.put(textureId.toString(), candidate);
                // 用原名建 AtlasTexture：UV/图集格仍按原 id 记账，顶替只影响像素来源
                return aliased.map(bytes -> decode(textureId, bytes));
            }
        }
        return Optional.empty();
    }

    /**
     * 本次运行里被别名顶替的贴图（原 id → 实际用到的 id），按原 id 排序。
     * 空表示所有贴图都按原名命中。
     */
    public List<Map.Entry<String, String>> substitutions() {
        List<Map.Entry<String, String>> out = new ArrayList<>(substitutions.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return List.copyOf(out);
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
