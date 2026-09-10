package online.yudream.voxelith.world.application;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * scan 链路用例：读 level.dat 判版本 → 扫描各维度 region 目录 → 区块清单与渲染包围盒落盘。
 * 只读 region 头表（不解析区块 NBT），十万级区块存档也能秒级完成。
 */
public class ScanWorldUseCase {

    /** 维度 id → 相对存档根的目录。 */
    private static final Map<String, String> DIMENSION_DIRS = Map.of(
            "minecraft:overworld", "",
            "minecraft:the_nether", "DIM-1",
            "minecraft:the_end", "DIM1");

    private final WorldReader worldReader;
    private final ScanArtifactSink sink;

    public ScanWorldUseCase(WorldReader worldReader, ScanArtifactSink sink) {
        this.worldReader = worldReader;
        this.sink = sink;
    }

    public ScanOutcome scan(ScanCommand command) {
        LevelInfo level = worldReader.readLevelInfo(command.worldDir());

        Map<String, Map<RegionPos, List<WorldReader.ChunkRef>>> dimensions = new LinkedHashMap<>();
        int regionCount = 0;
        int chunkCount = 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Map.Entry<String, String> dimension : DIMENSION_DIRS.entrySet()) {
            Path dimensionDir = dimension.getValue().isEmpty()
                    ? command.worldDir()
                    : command.worldDir().resolve(dimension.getValue());
            if (!Files.isDirectory(dimensionDir)) {
                continue;
            }
            Map<RegionPos, List<WorldReader.ChunkRef>> regions = worldReader.scanRegions(dimensionDir);
            if (regions.isEmpty()) {
                continue;
            }
            dimensions.put(dimension.getKey(), regions);
            for (List<WorldReader.ChunkRef> chunks : regions.values()) {
                regionCount++;
                for (WorldReader.ChunkRef chunk : chunks) {
                    chunkCount++;
                    minX = Math.min(minX, chunk.pos().x());
                    minZ = Math.min(minZ, chunk.pos().z());
                    maxX = Math.max(maxX, chunk.pos().x());
                    maxZ = Math.max(maxZ, chunk.pos().z());
                }
            }
        }

        sink.writeScanArtifacts(command.outputDir(), level.versionName(), level.dataVersion(), dimensions);

        return new ScanOutcome(
                level.versionName(), level.dataVersion(),
                dimensions.size(), regionCount, chunkCount,
                chunkCount == 0 ? 0 : minX, chunkCount == 0 ? 0 : minZ,
                chunkCount == 0 ? 0 : maxX, chunkCount == 0 ? 0 : maxZ);
    }
}
