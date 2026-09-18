package online.yudream.voxelith.tile.domain.manifest;

import java.util.List;

/**
 * 地图清单（manifest.json 的领域模型）。前端凭它发现瓦片、校验缓存（sha1）、构建包围盒。
 */
public record MapManifest(int formatVersion, String mapId, String name, String version, String generatedAt,
                          Settings settings, float[] boundsMin, float[] boundsMax,
                          AtlasRef atlas, List<TileEntry> tiles, List<LodAtlasRef> lodAtlases) {

    public MapManifest {
        tiles = tiles == null ? List.of() : List.copyOf(tiles);
        lodAtlases = lodAtlases == null ? List.of() : List.copyOf(lodAtlases);
    }

    /** 兼容构造：不带 LOD 图集页（老清单，或增量路径不产生页时）。 */
    public MapManifest(int formatVersion, String mapId, String name, String version, String generatedAt,
                       Settings settings, float[] boundsMin, float[] boundsMax,
                       AtlasRef atlas, List<TileEntry> tiles) {
        this(formatVersion, mapId, name, version, generatedAt, settings, boundsMin, boundsMax,
                atlas, tiles, List.of());
    }

    /** @param hiresTileSize hires 瓦片边长（方块数） @param lodCount LOD 层级数（一期恒为 1，仅 hires） */
    public record Settings(int hiresTileSize, int lodCount) {
    }

    /** @param url 相对地图根的图集地址 @param size 图集边长（像素） @param textureCount 入集贴图数 */
    public record AtlasRef(String url, int size, int textureCount) {
    }

    /**
     * LOD 层级共享图集页：该层全部瓦片的航拍色图拼成一页，瓦片 glb 只带指向本页的 UV。
     * 前端每层加载一张纹理即可，无需逐瓦片解码色图。
     *
     * @param level    LOD 层级（≥ 1）
     * @param url      相对地图根的图集页地址
     * @param slotSize 槽位边长（像素）
     * @param sha1     页内容哈希（前端缓存版本戳）
     */
    public record LodAtlasRef(int level, String url, int slotSize, String sha1) {
    }

    /**
     * @param url  相对地图根的瓦片地址（tiles/hires/{x}/{z}.glb）
     * @param sha1 瓦片内容哈希（前端缓存失效依据）
     * @param min/max 世界包围盒
     */
    public record TileEntry(int level, int x, int z, String url, String sha1, int bytes, int quads,
                            float[] min, float[] max) {
    }
}
