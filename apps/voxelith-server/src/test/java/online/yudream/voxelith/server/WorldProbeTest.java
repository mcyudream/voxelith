package online.yudream.voxelith.server;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 临时探针：打印某个存档在指定区块的截面/方块读取情况，用于定位「bake 出 0 个方块」。
 * 存档不存在时自动跳过（本机专用，不进 CI 断言）。
 */
class WorldProbeTest {

    private static final Path WORLD = Path.of(
            "A:/liu23/Documents/Graduation project/map/250323西南科大青义存档");

    @Test
    void probe() {
        if (!Files.isDirectory(WORLD)) {
            System.out.println("跳过：存档不存在 " + WORLD);
            return;
        }
        AnvilWorldReader reader = new AnvilWorldReader();
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(
                WORLD, "minecraft:overworld")) {
            // 取样：窗口 X[2944,3456] Z[256,768] 里的几个区块
            int[][] samples = {{184, 16}, {200, 30}, {216, 48}};
            for (int[] sample : samples) {
                ChunkPos pos = new ChunkPos(sample[0], sample[1]);
                int[] ys = world.sectionYs(pos);
                System.out.printf("%n区块 %s：sectionYs=%s%n", pos, java.util.Arrays.toString(ys));
                ChunkData data = reader.readChunk(WORLD, pos).orElse(null);
                if (data == null) {
                    System.out.println("  readChunk 为空");
                    continue;
                }
                for (ChunkSection section : data.orderedSections()) {
                    System.out.printf("  section y=%d allAir=%s blockStates=%s biomes=%s%n",
                            section.y(), section.isAllAir(),
                            section.blockStates() == null ? "null" : "ok",
                            section.biomes() == null ? "null" : "ok");
                }
                for (int y : new int[]{-64, 0, 60, 64, 70, 80, 100, 200}) {
                    System.out.printf("  blockStateAt(%d,%d,%d) = %s%n",
                            sample[0] * 16 + 8, y, sample[1] * 16 + 8,
                            world.blockStateAt(sample[0] * 16 + 8, y, sample[1] * 16 + 8));
                }
            }
        }
    }
}
