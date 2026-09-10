package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.testfixtures.LegacySyntheticWorldBuilder;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 多版本回归：同一读取器按区块形状分派现代 / 1.13–1.17 / 1.12- 解析器。 */
class MultiVersionDispatchTest {

    @TempDir
    Path worldDir;

    @Test
    void dispatchesPerChunkFormatInMixedWorld() {
        // 升级存档场景：新格式区块（region 0,0）与未迁移的 1.12 区块（region 1,0）共存
        new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.NUMERIC_1_12)
                .setBlock(515, 64, 3, 1, 1) // chunk (32,0) → region (1,0)，granite
                .write(worldDir);
        new SyntheticWorldBuilder()
                .flatGround(0, 0, 16, 16, 60)
                .setBlock(5, 61, 5, "minecraft:oak_log[axis=y]")
                .write(worldDir); // 后写，level.dat 为现代版本

        WorldReader reader = new AnvilWorldReader();
        LevelInfo level = reader.readLevelInfo(worldDir);
        assertThat(level.dataVersion()).isEqualTo(SyntheticWorldBuilder.DATA_VERSION_1_20_1);

        Map<RegionPos, List<WorldReader.ChunkRef>> regions = reader.scanRegions(worldDir);
        assertThat(regions).containsKeys(new RegionPos(0, 0), new RegionPos(1, 0));

        ChunkData modern = reader.readChunk(worldDir, new ChunkPos(0, 0)).orElseThrow();
        assertThat(modern.dataVersion()).isEqualTo(SyntheticWorldBuilder.DATA_VERSION_1_20_1);
        assertThat(modern.sectionAt(3).orElseThrow().blockStateAt(5, 61 - 48, 5).toString())
                .isEqualTo("minecraft:oak_log[axis=y]");

        ChunkData legacy = reader.readChunk(worldDir, new ChunkPos(32, 0)).orElseThrow();
        assertThat(legacy.dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_12_2);
        assertThat(legacy.sectionAt(4).orElseThrow().blockStateAt(3, 0, 3).toString())
                .isEqualTo("minecraft:granite");
    }

    @Test
    void readsPalettedLegacyWorldThroughSameReader() {
        new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.PALETTED_1_15)
                .setBlock(3, 64, 3, "minecraft:oak_log[axis=z]")
                .write(worldDir);

        ChunkData chunk = new AnvilWorldReader().readChunk(worldDir, new ChunkPos(0, 0)).orElseThrow();
        assertThat(chunk.dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_15_2);
        assertThat(chunk.sectionAt(4).orElseThrow().blockStateAt(3, 0, 3).toString())
                .isEqualTo("minecraft:oak_log[axis=z]");
    }
}
