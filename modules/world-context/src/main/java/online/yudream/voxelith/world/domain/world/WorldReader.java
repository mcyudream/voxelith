package online.yudream.voxelith.world.domain.world;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 世界读取端口（版本适配 SPI 的承载接口）。实现位于 infrastructure 层。
 * 一期仅现代格式（1.18+ 区块布局，1.20.1 验证）；legacy 适配器 Phase 4 插拔。
 */
public interface WorldReader {

    /** 读 level.dat 元信息。 */
    LevelInfo readLevelInfo(Path worldDir);

    /**
     * 列出某维度下全部 region 及其内含区块坐标。
     *
     * @param dimensionDir 维度目录（存档根为主世界，DIM-1/DIM1 为下界/末地）
     * @return region → 区块坐标与最后修改时间戳
     */
    Map<RegionPos, List<ChunkRef>> scanRegions(Path dimensionDir);

    /** 读取整个 region 的全部区块 NBT 解析结果（不存在的区块跳过）。 */
    List<ChunkData> readRegion(Path dimensionDir, RegionPos region);

    /** 读取单个区块。 */
    Optional<ChunkData> readChunk(Path dimensionDir, ChunkPos pos);

    /**
     * 区块引用（扫描阶段的轻量描述，不解析 NBT）。
     */
    record ChunkRef(ChunkPos pos, int timestampSeconds) {
    }
}
