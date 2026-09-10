package online.yudream.voxelith.sharedkernel.vo;

/**
 * 区块坐标（16×16 方块）。
 */
public record ChunkPos(int x, int z) {

    public RegionPos toRegionPos() {
        return new RegionPos(x >> 5, z >> 5);
    }

    /** 区块在所属 region 内的局部坐标（0..31）。 */
    public int localX() {
        return x & 31;
    }

    public int localZ() {
        return z & 31;
    }
}
