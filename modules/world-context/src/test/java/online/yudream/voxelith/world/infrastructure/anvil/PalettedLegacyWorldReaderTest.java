package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.testfixtures.LegacySyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 1.15 调色板 legacy 世界 golden 测试（旧跨 long 打包 + int[1024] quart 群系）。 */
class PalettedLegacyWorldReaderTest {

    @TempDir
    Path worldDir;

    private static final String[] STATES = {
            "minecraft:stone", "minecraft:dirt", "minecraft:granite", "minecraft:diorite",
            "minecraft:andesite", "minecraft:oak_log[axis=x]", "minecraft:oak_log[axis=z]",
            "minecraft:red_wool", "minecraft:blue_wool", "minecraft:glass",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:cobblestone",
            "minecraft:bricks", "minecraft:sandstone", "minecraft:gravel",
            "minecraft:sand", "minecraft:clay", "minecraft:obsidian", "minecraft:netherrack"
    };

    @Test
    void parsesCrossLongPackedPalette() {
        ChunkPos pos = new ChunkPos(0, 0);
        LegacySyntheticWorldBuilder builder =
                new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.PALETTED_1_15);
        // 20 个不同状态 + air = 21 项调色板 → 5 bit/项，1.15 打包必然跨 long 边界
        for (int i = 0; i < STATES.length; i++) {
            builder.setBlock(i % 4, 64, i / 4, STATES[i]);
        }
        builder.write(worldDir);

        WorldReader reader = new AnvilWorldReader();
        LevelInfo level = reader.readLevelInfo(worldDir);
        assertThat(level.versionName()).isEqualTo("1.15.2");
        assertThat(level.dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_15_2);

        Optional<ChunkData> chunk = reader.readChunk(worldDir, pos);
        assertThat(chunk).isPresent();
        assertThat(chunk.get().dataVersion()).isEqualTo(LegacySyntheticWorldBuilder.DATA_VERSION_1_15_2);

        ChunkSection section = chunk.get().sectionAt(4).orElseThrow();
        assertThat(section.blockStates().bitsPerEntry()).isEqualTo(5);
        for (int i = 0; i < STATES.length; i++) {
            assertThat(section.blockStateAt(i % 4, 0, i / 4).toString())
                    .as("i=" + i).isEqualTo(STATES[i]);
        }
        assertThat(section.blockStateAt(3, 3, 3).toString()).isEqualTo("minecraft:air");
    }

    @Test
    void mapsQuartBiomesByGlobalSectionY() {
        ChunkPos pos = new ChunkPos(0, 0);
        int[] quartBiomes = new int[1024];
        Arrays.fill(quartBiomes, 1);                       // plains
        quartBiomes[16 * 16 + 0 * 4 + 0] = 21;             // section 4 的 qy=0,qz=0,qx=0 → jungle

        new LegacySyntheticWorldBuilder(LegacySyntheticWorldBuilder.Format.PALETTED_1_15)
                .setBlock(0, 64, 0, "minecraft:stone")
                .setBiomes(pos, quartBiomes)
                .write(worldDir);

        ChunkData chunk = new AnvilWorldReader().readChunk(worldDir, pos).orElseThrow();
        ChunkSection section = chunk.sectionAt(4).orElseThrow();

        assertThat(section.biomes()).isNotNull();
        assertThat(section.biomes().get(0)).isEqualTo("minecraft:jungle");
        assertThat(section.biomes().get(63)).isEqualTo("minecraft:plains");
    }
}
