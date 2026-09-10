package online.yudream.voxelith.world.application;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.dto.BlockStateData;

import java.util.Optional;

/**
 * 世界方块访问：世界上下文对外的查询契约（烘焙链路消费）。
 * 以世界绝对坐标随机访问；实现按区块缓存。线程安全。
 */
public interface WorldBlockAccess extends AutoCloseable {

    /** 世界绝对坐标处的方块状态；区块不存在或截面缺失时为 empty。 */
    Optional<BlockStateData> blockStateAt(int x, int y, int z);

    /** 天空光 0..15；无数据时返回 0。 */
    int skyLightAt(int x, int y, int z);

    /** 方块光 0..15；无数据时返回 0。 */
    int blockLightAt(int x, int y, int z);

    /** 指定区块存在的截面 Y 序号（升序）；区块不存在时为空数组。 */
    int[] sectionYs(ChunkPos chunk);

    /** 世界绝对坐标处的群系 id（如 minecraft:plains）；无数据时为 empty。 */
    default Optional<String> biomeAt(int x, int y, int z) {
        return Optional.empty();
    }

    @Override
    default void close() {
    }
}
