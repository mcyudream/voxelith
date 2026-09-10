package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnvilWorldBlockAccessInvalidateRegionTest {

    @TempDir
    Path worldDir;

    @Test
    void invalidateRegionDropsCachedChunksSoRereadSeesNewBlocks() {
        new SyntheticWorldBuilder()
                .setBlock(0, 64, 0, "minecraft:stone")
                .write(worldDir);

        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            assertThat(world.blockStateAt(0, 64, 0).orElseThrow().block())
                    .isEqualTo("minecraft:stone");
            assertThat(world.sectionYs(new ChunkPos(0, 0))).isNotEmpty();

            new SyntheticWorldBuilder()
                    .setBlock(0, 70, 0, "minecraft:dirt")
                    .write(worldDir);

            // 缓存未失效时仍看到旧区块（y=64 仍是 stone，没有 dirt）
            assertThat(world.blockStateAt(0, 64, 0).orElseThrow().block())
                    .isEqualTo("minecraft:stone");
            assertThat(world.blockStateAt(0, 70, 0)
                    .filter(s -> "minecraft:dirt".equals(s.block()))).isEmpty();

            world.invalidateRegion(new RegionPos(0, 0));
            assertThat(world.blockStateAt(0, 70, 0).orElseThrow().block())
                    .isEqualTo("minecraft:dirt");
        }
    }
}
