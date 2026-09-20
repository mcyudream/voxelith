package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.BakeCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * region 窗口裁剪：只枚举真实存在的 region 文件，且必须与请求的方块范围有交集。
 *
 * <p>燕理存档的哨站撒到 Z≈100 万方块外，早先「在极值之间铺满矩形」会把窗口撑到八万多个
 * region（8400 万次槽位探测、40 秒纯扫描）。这个测试锁住「按文件 + 按方块范围裁剪」。</p>
 */
class RenderRegionWindowTest {

    private static RenderMapOptions options(Integer minX, Integer maxX, Integer minZ, Integer maxZ) {
        return new RenderMapOptions(
                Path.of("A:/maps/world"), "minecraft:overworld", "m", "m",
                List.of(Path.of("A:/packs/client.jar")),
                Path.of("A:/work"), Path.of("A:/data/maps"), null,
                null, null, null, null,
                0, 0, true, BakeCommand.NO_MIN_Y, minX, maxX, minZ, maxZ,
                "1.20.1", "0.16.14", false, List.of(), 0, 0, true);
    }

    private static Path regionDir(Path dir, String... files) throws IOException {
        Path region = Files.createDirectories(dir.resolve("region"));
        for (String name : files) {
            Files.writeString(region.resolve(name), "");
        }
        return region;
    }

    @Test
    void keepsOnlyRegionsIntersectingTheBlockRange(@TempDir Path tmp) throws IOException {
        Path region = regionDir(tmp, "r.0.0.mca", "r.-2.0.mca", "r.1953.0.mca", "level.dat");

        // 只框了 X[-1024,-513] Z[0,511]（= r.-2.0 的右上角），远处的哨站 r.1953.0 不该进窗口
        List<int[]> window = RenderMapCli.regionWindow(options(-1024, -513, 0, 511), region);

        assertThat(window).containsExactly(new int[]{-2, 0});
    }

    @Test
    void withoutBlockRangeEveryExistingRegionIsKept(@TempDir Path tmp) throws IOException {
        Path region = regionDir(tmp, "r.0.0.mca", "r.1953.0.mca");

        List<int[]> window = RenderMapCli.regionWindow(options(null, null, null, null), region);

        assertThat(window).containsExactly(new int[]{0, 0}, new int[]{1953, 0});
    }

    @Test
    void honoursExplicitRegionEdges(@TempDir Path tmp) throws IOException {
        Path region = regionDir(tmp, "r.0.0.mca", "r.1.0.mca", "r.2.0.mca");
        RenderMapOptions base = options(null, null, null, null);
        RenderMapOptions limited = new RenderMapOptions(
                base.worldDir(), base.dimension(), base.mapId(), base.mapName(), base.packs(),
                base.workDir(), base.publishDir(), null,
                1, 1, null, null,
                base.maxLevel(), base.sampleChunks(), base.lodAtlas(), base.minY(),
                null, null, null, null,
                base.mcVersion(), base.loaderVersion(), base.skipHarvest(), base.workerClasspath(),
                base.maxChunks(), base.batchChunks(), base.meshopt());

        assertThat(RenderMapCli.regionWindow(limited, region)).containsExactly(new int[]{1, 0});
    }
}
