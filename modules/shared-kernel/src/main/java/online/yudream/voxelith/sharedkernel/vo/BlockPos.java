package online.yudream.voxelith.sharedkernel.vo;

/**
 * 方块坐标（世界内绝对坐标）。
 */
public record BlockPos(int x, int y, int z) {

    public ChunkPos toChunkPos() {
        return new ChunkPos(x >> 4, z >> 4);
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}
