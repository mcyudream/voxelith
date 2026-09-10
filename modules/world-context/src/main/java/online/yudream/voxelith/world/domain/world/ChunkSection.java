package online.yudream.voxelith.world.domain.world;

/**
 * 一个 16×16×16 区块截面（1.18+ 格式）。
 *
 * @param y           截面序号（1.20.1 主世界 -4~19）
 * @param blockStates 方块状态调色板容器
 * @param biomes      群系调色板容器（4×4×4）
 * @param skyLight    天空光 nibble 数组（2048 字节），可为空
 * @param blockLight  方块光 nibble 数组（2048 字节），可为空
 */
public record ChunkSection(
        int y,
        PalettedContainer<BlockStateSpec> blockStates,
        PalettedContainer<String> biomes,
        byte[] skyLight,
        byte[] blockLight) {

    public boolean isAllAir() {
        return blockStates.palette().size() == 1
                && "minecraft:air".equals(blockStates.palette().getFirst().block().toString());
    }

    /** 读截面内局部坐标的方块状态。 */
    public BlockStateSpec blockStateAt(int localX, int localY, int localZ) {
        return blockStates.get(localY * 256 + localZ * 16 + localX);
    }
}
