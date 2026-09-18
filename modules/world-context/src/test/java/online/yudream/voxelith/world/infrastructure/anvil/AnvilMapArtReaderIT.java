package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.MapArtFrame;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对真实 Paper 存档的地图画读取集成测试：需要本机存在该存档，不存在则跳过（不拖垮 CI）。
 *
 * <p>覆盖两个容易踩坑的点：展示框是<b>实体</b>且 Paper 把它们放在独立的 entities/ 目录
 * （不在 region 方块数据里），地图颜色在 data/map_*.dat。</p>
 */
class AnvilMapArtReaderIT {

    private static final Path SAVE = Path.of(
            "A:/liu23/Documents/Graduation project/map/燕京理工学院复原工程(2024版)");

    @Test
    void readsItemFramesHoldingMapsFromPaperEntitiesFolder() {
        assumeTrue(Files.isDirectory(SAVE), "本机没有该存档，跳过");

        AnvilMapArtReader reader = new AnvilMapArtReader(SAVE);
        List<MapArtFrame> frames = reader.frames(new RegionPos(-2, 0));

        assertThat(frames).as("region r.-2.0 里的地图画展示框").isNotEmpty();
        MapArtFrame first = frames.getFirst();
        assertThat(first.mapId()).isNotNegative();
        assertThat(first.facing()).isIn("down", "up", "north", "south", "west", "east");
    }

    @Test
    void readsMapColorsAndConvertsToArgb() {
        assumeTrue(Files.isDirectory(SAVE), "本机没有该存档，跳过");

        AnvilMapArtReader reader = new AnvilMapArtReader(SAVE);
        List<MapArtFrame> frames = reader.frames(new RegionPos(-2, 0));
        assumeTrue(!frames.isEmpty(), "没有展示框，跳过");

        int[] colors = reader.mapColors(frames.getFirst().mapId()).orElseThrow();
        assertThat(colors).hasSize(AnvilMapArtReader.MAP_SIZE * AnvilMapArtReader.MAP_SIZE);
        // 地图画是「已填充」的，绝大多数像素必须是不透明的（不是空白地图）
        long opaque = java.util.Arrays.stream(colors).filter(c -> (c >>> 24) != 0).count();
        assertThat(opaque).as("不透明像素占比").isGreaterThan(colors.length / 2);
    }

    @Test
    void packsMapPaletteBytesLikeVanilla() {
        // 基色 0（NONE）透明；(色 id<<2|档位) 的档位倍率 LOW/NORMAL/HIGH/LOWEST
        assertThat(AnvilMapArtReader.toArgb((byte) 0)).isZero();
        // 色 id 1（GRASS 0x7FB238），档位 2 = 满亮度
        int bright = AnvilMapArtReader.toArgb((byte) ((1 << 2) | 2));
        assertThat((bright >> 8) & 0xFF).isEqualTo(0xB2);
        // 同色最低亮度应明显更暗
        int dark = AnvilMapArtReader.toArgb((byte) ((1 << 2) | 3));
        assertThat((dark >> 8) & 0xFF).isLessThan((bright >> 8) & 0xFF);
    }
}
