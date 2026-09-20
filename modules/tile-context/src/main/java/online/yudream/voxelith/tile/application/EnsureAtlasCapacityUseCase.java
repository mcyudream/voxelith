package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.sharedkernel.vo.MissingTexture;
import online.yudream.voxelith.tile.domain.atlas.AtlasExpander;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import online.yudream.voxelith.tile.domain.atlas.TexturePixelSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 增量扩图集用例：本次要渲染的方块用到了已发布图集里没有的贴图时，
 * 把这些贴图**追加**进图集（老单元格不动），并落盘替换已发布图集。
 *
 * <p>没有这一步时的表现：新出现的贴图在 {@code AtlasLayout.mapUv} 里查不到，
 * 整片渲染成品红兜底格——经典的「存档里新放了 mod 方块，重跑增量后一片紫」。</p>
 *
 * <p>扩容是**幂等**的：贴图已经在布局里就直接返回原图集，不做任何 IO。</p>
 */
public class EnsureAtlasCapacityUseCase {

    /**
     * @param atlas    本次 tile 应使用的图集（可能已扩容）
     * @param added    本次真正追加进图集的贴图 id
     * @param missing  请求了但资源包里找不到的贴图 id（仍走兜底格，由调用方报给用户）
     * @param expanded true = 发生了扩容并已写回已发布图集
     */
    public record Result(AtlasReuse atlas, List<String> added, List<String> missing, boolean expanded) {
    }

    private final PublishedAtlas published;
    private final TexturePixelSource pixelSource;
    private final ImageCodec imageCodec;
    private final AtlasExpander expander = new AtlasExpander();

    public EnsureAtlasCapacityUseCase(PublishedAtlas published, TexturePixelSource pixelSource,
                                      ImageCodec imageCodec) {
        this.published = published;
        this.pixelSource = pixelSource;
        this.imageCodec = imageCodec;
    }

    /**
     * @param mapId        地图 id（写回已发布图集用）
     * @param current      已发布图集（null = 调用方本就要重新打包，直接原样返回 null）
     * @param requiredIds  本次渲染会用到的贴图 id（可以多给，已在图集里的会被忽略）
     */
    public Result ensure(String mapId, AtlasReuse current, Collection<String> requiredIds) {
        if (current == null || requiredIds == null || requiredIds.isEmpty()) {
            return new Result(current, List.of(), List.of(), false);
        }
        TreeSet<String> needed = new TreeSet<>();
        for (String id : requiredIds) {
            if (id == null || id.isBlank() || MissingTexture.ID.equals(id)) {
                continue;
            }
            if (!current.layout().cellIndex().containsKey(id)) {
                needed.add(id);
            }
        }
        if (needed.isEmpty()) {
            return new Result(current, List.of(), List.of(), false);
        }

        List<AtlasTexture> textures = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : needed) {
            Optional<AtlasTexture> texture = pixelSource.load(Identifier.parse(id));
            if (texture.isEmpty()) {
                missing.add(id);
            } else {
                textures.add(texture.get());
            }
        }
        if (textures.isEmpty()) {
            // 全是取不到的贴图：不扩容，让它们继续落兜底格（与全量打包的行为一致）
            return new Result(current, List.of(), List.copyOf(missing), false);
        }

        int[] oldPixels = imageCodec.decodePng(current.png());
        AtlasExpander.Expansion expansion = expander.expand(current.layout(), oldPixels, textures);
        if (expansion.unchanged()) {
            return new Result(current, List.of(), List.copyOf(missing), false);
        }

        byte[] png = imageCodec.encodePng(expansion.layout().width(), expansion.layout().height(),
                expansion.argb());
        AtlasPacker.AtlasResult atlasResult = new AtlasPacker.AtlasResult(
                expansion.layout(), expansion.layout().width(), expansion.argb());
        published.save(mapId, atlasResult, png);
        return new Result(new AtlasReuse(expansion.layout(), png),
                expansion.added(), List.copyOf(missing), true);
    }
}
