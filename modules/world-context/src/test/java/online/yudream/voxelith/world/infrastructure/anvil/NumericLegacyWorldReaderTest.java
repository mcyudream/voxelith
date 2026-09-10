package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.infrastructure.legacy.LegacyFlatteningTable;
import online.yudream.voxelith.world.testfixtures.LegacySyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 1.12 数字 ID/meta 世界 golden 测试。 */
class NumericLegacyWorldReaderTest {

    @TempDir
    Path worldDir;

    @Test
    void flattensNumericIdsThroughMappingTable() {
        new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.NUMERIC_1_12)
                .setBlock(2, 64, 3, 1, 0)     // stone
                .setBlock(5, 64, 7, 1, 1)     // granite
                .setBlock(1, 64, 1, 17, 4)    // oak_log[axis=x]
                .setBlock(0, 64, 0, 35, 14)   // red_wool
                .setBlock(9, 64, 9, 53, 5)    // oak_stairs 倒置朝西
                .setBlock(12, 64, 12, 255, 0) // structure_block（id>255 走 Add nibble）
                .setBlock(14, 64, 14, 254, 3) // 未知 id → fallback stone
                .write(worldDir);

        WorldReader reader = new AnvilWorldReader();
        LevelInfo level = reader.readLevelInfo(worldDir);
        assertThat(level.versionName()).isEqualTo("1.12.2");
        assertThat(level.dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_12_2);

        Optional<ChunkData> chunk = reader.readChunk(worldDir, new ChunkPos(0, 0));
        assertThat(chunk).isPresent();
        assertThat(chunk.get().dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_12_2);

        ChunkSection section = chunk.get().sectionAt(4).orElseThrow(); // y=64..79
        assertThat(section.blockStateAt(2, 0, 3).toString()).isEqualTo("minecraft:stone");
        assertThat(section.blockStateAt(5, 0, 7).toString()).isEqualTo("minecraft:granite");
        assertThat(section.blockStateAt(1, 0, 1).toString()).isEqualTo("minecraft:oak_log[axis=x]");
        assertThat(section.blockStateAt(0, 0, 0).toString()).isEqualTo("minecraft:red_wool");
        assertThat(section.blockStateAt(9, 0, 9).toString())
                .isEqualTo("minecraft:oak_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
        assertThat(section.blockStateAt(12, 0, 12).toString())
                .isEqualTo("minecraft:structure_block[mode=data]");
        assertThat(section.blockStateAt(14, 0, 14).toString())
                .isEqualTo(LegacyFlatteningTable.FALLBACK_BLOCK);
        // 未知 id 计入降级统计
        assertThat(LegacyFlatteningTable.shared().unknownStats()).containsKey((254 << 4) | 3);
    }

    @Test
    void readsNibbleLightAndColumnBiomes() {
        ChunkPos pos = new ChunkPos(0, 0);
        byte[] columnBiomes = new byte[256];
        Arrays.fill(columnBiomes, (byte) 4);      // forest
        columnBiomes[2 * 16 + 10] = (byte) 21;    // 一列 jungle（quart 采样点 qz=0,qx=2）

        new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.NUMERIC_1_12)
                .setBlock(0, 64, 0, 1, 0)
                .setBiomes(pos, columnBiomes)
                .write(worldDir);

        ChunkData chunk = new AnvilWorldReader().readChunk(worldDir, pos).orElseThrow();
        ChunkSection section = chunk.sectionAt(4).orElseThrow();

        assertThat(section.skyLight()).hasSize(2048);
        assertThat(section.skyLight()[0]).isEqualTo((byte) 0xff);
        assertThat(section.blockLight()).hasSize(2048);

        assertThat(section.biomes()).isNotNull();
        assertThat(section.biomes().get(2)).isEqualTo("minecraft:jungle"); // qy=0,qz=0,qx=2
        assertThat(section.biomes().get(0)).isEqualTo("minecraft:forest");
    }
}
