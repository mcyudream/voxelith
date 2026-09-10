package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.world.ChunkData;

/**
 * 区块 NBT 负载解析器（版本适配 SPI 的插拔点）。
 * 按 NBT 形状而非全局版本号分派——升级过的存档中不同区块可能保持各自写入时的格式。
 */
public interface ChunkPayloadParser {

    /** 该解析器是否认识此区块 NBT 布局。 */
    boolean supports(CompoundTag chunkRoot);

    /** 解析区块。调用前必须先经 {@link #supports} 判定。 */
    ChunkData parse(ChunkPos pos, CompoundTag chunkRoot);
}
