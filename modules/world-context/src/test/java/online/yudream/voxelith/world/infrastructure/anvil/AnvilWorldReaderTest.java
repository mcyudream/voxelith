package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AnvilWorldReaderTest {

    @TempDir
    Path worldDir;

    @Test
    void readsSyntheticWorldEndToEnd() {
        new SyntheticWorldBuilder()
                .flatGround(0, 0, 32, 32, 60)
                .setBlock(5, 61, 5, "minecraft:oak_log[axis=y]")
                .write(worldDir);

        WorldReader reader = new AnvilWorldReader();

        LevelInfo level = reader.readLevelInfo(worldDir);
        assertThat(level.versionName()).isEqualTo("1.20.1");
        assertThat(level.dataVersion()).isEqualTo(3465);

        Map<RegionPos, List<WorldReader.ChunkRef>> regions = reader.scanRegions(worldDir);
        assertThat(regions).containsKey(new RegionPos(0, 0));
        // 32×32 方块 = 2×2 区块
        assertThat(regions.get(new RegionPos(0, 0))).hasSize(4);

        Optional<ChunkData> chunk = reader.readChunk(worldDir, new ChunkPos(0, 0));
        assertThat(chunk).isPresent();
        ChunkSection surfaceSection = chunk.get().sectionAt(3).orElseThrow(); // y=48..63
        assertThat(surfaceSection.blockStateAt(5, 60 - 48, 5).block().toString())
                .isEqualTo("minecraft:grass_block");
        ChunkSection above = chunk.get().sectionAt(3).orElseThrow();
        assertThat(above.blockStateAt(5, 61 - 48, 5).toString())
                .isEqualTo("minecraft:oak_log[axis=y]");
        assertThat(above.blockStateAt(5, 61 - 48, 5).properties())
                .containsEntry("axis", "y");
        assertThat(above.skyLight()).hasSize(2048);
    }

    @Test
    void missingChunkIsEmptyNotError() {
        new SyntheticWorldBuilder().flatGround(0, 0, 16, 16, 60).write(worldDir);

        WorldReader reader = new AnvilWorldReader();

        assertThat(reader.readChunk(worldDir, new ChunkPos(9, 9))).isEmpty();
        assertThat(reader.readRegion(worldDir, new RegionPos(0, 0))).hasSize(1);
    }
}
