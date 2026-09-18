package online.yudream.voxelith.tile.application;

/**
 * LOD 层级图集页（跨上下文 DTO）。
 *
 * <p>全量生成时，一层内所有 LOD 瓦片的航拍色图被拼成一张 {@code tiles/lod/{level}/lod-atlas.png}，
 * 瓦片 glb 只带指向该页的 UV（不再逐瓦片内嵌色图）。这样前端每层只需加载/解码一张纹理，
 * 而不是每片瓦片一张（2176 片 → 2000+ 纹理对象与同等数量的 PNG 解码）。</p>
 *
 * <p>放在 tile.application：lod-context 需要返回它，而跨上下文只能访问对方 application 层。</p>
 *
 * @param level     LOD 层级（≥ 1）
 * @param url       相对地图根的图集页地址
 * @param slotSize  槽位边长（像素）；层级越深越小
 * @param sha1      图集页内容哈希（前端缓存版本戳）
 */
public record LodAtlasPage(int level, String url, int slotSize, String sha1) {

    public LodAtlasPage {
        if (level < 1) {
            throw new IllegalArgumentException("LOD 图集页层级必须 ≥ 1，收到: " + level);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("图集页 url 不能为空");
        }
        if (slotSize < 1) {
            throw new IllegalArgumentException("槽位边长必须为正，收到: " + slotSize);
        }
    }
}
