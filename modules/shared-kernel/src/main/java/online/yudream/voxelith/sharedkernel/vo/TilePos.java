package online.yudream.voxelith.sharedkernel.vo;

/**
 * 瓦片坐标。level 为 LOD 层级（0 = hires 最精细）。
 * hires 瓦片默认 32×32 方块（2×2 区块），LOD 瓦片随层级按 2 的幂扩大覆盖范围。
 */
public record TilePos(int level, int x, int z) {

    public static TilePos hires(int x, int z) {
        return new TilePos(0, x, z);
    }

    public boolean isHires() {
        return level == 0;
    }

    /** 该层级瓦片覆盖的方块边长（hires 瓦片边长 × 2^level）。 */
    public int coverage(int hiresTileSize) {
        return hiresTileSize << level;
    }
}
