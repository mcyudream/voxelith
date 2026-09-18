package online.yudream.voxelith.tile.application;

import java.util.List;
import java.util.Map;

/**
 * 清单增量补丁：url → 新 sha1（空串 = 删除），尚未出现在清单中的新瓦片摘要，
 * 以及新增/替换的 LOD 层级图集页。
 * 编排域只传本 DTO，不引用 tile.domain.MapManifest。
 *
 * @param lodAtlasPages 新的 LOD 图集页（按 level 替换既有页）；空 = 沿用清单中已发布的页
 */
public record ManifestPatch(Map<String, String> sha1ByUrl, List<NewTile> inserts,
                            List<LodAtlasPage> lodAtlasPages) {

    public ManifestPatch {
        sha1ByUrl = Map.copyOf(sha1ByUrl);
        inserts = List.copyOf(inserts);
        lodAtlasPages = lodAtlasPages == null ? List.of() : List.copyOf(lodAtlasPages);
    }

    public ManifestPatch(Map<String, String> sha1ByUrl, List<NewTile> inserts) {
        this(sha1ByUrl, inserts, List.of());
    }

    public static ManifestPatch sha1Only(java.util.Map<String, String> sha1ByUrl) {
        return new ManifestPatch(sha1ByUrl, java.util.List.of());
    }

    /**
     * @param url   相对地图根的瓦片地址（与清单 TileEntry.url 对齐）
     * @param level LOD 层级（0 = hires）
     * @param x     瓦片 x
     * @param z     瓦片 z
     * @param sha1  内容哈希
     * @param bytes glb 字节数
     * @param quads 面数
     * @param min   世界包围盒最小点
     * @param max   世界包围盒最大点
     */
    public record NewTile(String url, int level, int x, int z, String sha1, int bytes, int quads,
                          float[] min, float[] max) {

        public static NewTile from(TileOutcome.TileSummary summary) {
            var pos = summary.pos();
            String url = pos.isHires()
                    ? "tiles/hires/" + pos.x() + "/" + pos.z() + ".glb"
                    : "tiles/lod/" + pos.level() + "/" + pos.x() + "/" + pos.z() + ".glb";
            return new NewTile(url, pos.level(), pos.x(), pos.z(), summary.sha1(),
                    summary.bytes(), summary.quads(), summary.min(), summary.max());
        }
    }
}
