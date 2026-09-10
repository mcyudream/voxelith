package online.yudream.voxelith.maps.domain;

/**
 * 地图渲染配置（聚合内的值对象）。
 *
 * @param hiresTileSize   hires 瓦片边长（方块），默认 32（2×2 区块）
 * @param lodCount        LOD 层级数（不含 hires 层）
 * @param lodFactor       LOD 逐级覆盖倍率
 * @param skyLight        初始日光强度 0.0~1.0
 * @param ambientLight    环境光下限 0.0~1.0
 * @param removeCavesBelowY 洞穴剔除阈值（低于该 Y 且无光照的面丢弃），null 关闭
 */
public record MapRenderSettings(
        int hiresTileSize,
        int lodCount,
        int lodFactor,
        double skyLight,
        double ambientLight,
        Integer removeCavesBelowY
) {
    public static MapRenderSettings defaults() {
        return new MapRenderSettings(32, 3, 5, 1.0, 0.0, 55);
    }
}
