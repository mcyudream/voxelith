package online.yudream.voxelith.tile.application;

/**
 * 清单增量补丁：url → 新 sha1（空串 = 删除），以及尚未出现在清单中的新瓦片摘要。
 * 编排域只传本 DTO，不引用 tile.domain.MapManifest。
 */
public record ManifestPatch(java.util.Map<String, String> sha1ByUrl, java.util.List<NewTile> inserts) {

    public ManifestPatch {
        sha1ByUrl = java.util.Map.copyOf(sha1ByUrl);
        inserts = java.util.List.copyOf(inserts);
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
