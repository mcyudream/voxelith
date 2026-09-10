package online.yudream.voxelith.tile.domain.manifest;

import java.util.List;

/**
 * 地图清单（manifest.json 的领域模型）。前端凭它发现瓦片、校验缓存（sha1）、构建包围盒。
 */
public record MapManifest(int formatVersion, String mapId, String name, String version, String generatedAt,
                          Settings settings, float[] boundsMin, float[] boundsMax,
                          AtlasRef atlas, List<TileEntry> tiles) {

    /** @param hiresTileSize hires 瓦片边长（方块数） @param lodCount LOD 层级数（一期恒为 1，仅 hires） */
    public record Settings(int hiresTileSize, int lodCount) {
    }

    /** @param url 相对地图根的图集地址 @param size 图集边长（像素） @param textureCount 入集贴图数 */
    public record AtlasRef(String url, int size, int textureCount) {
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
