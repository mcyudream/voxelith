package online.yudream.voxelith.resource.domain.biome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BiomeColorTableTest {

    private static BiomeEffects biome(double temp, double downfall, String modifier) {
        return new BiomeEffects(temp, downfall, null, null, modifier, null);
    }

    /** 构造 256×256 colormap，每个像素的 RGB 编码自身的 (x, y) 便于断言采样坐标。 */
    private static int[] coordinateColormap() {
        int[] map = new int[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                map[(y << 8) | x] = (x << 8) | (y & 0xFF);
            }
        }
        return map;
    }

    @Test
    void grassSamplesColormapWithVanillaMath() {
        BiomeColorTable table = new BiomeColorTable(coordinateColormap(), new int[0]);
        // plains：temp=0.8, downfall=0.4
        // x=(int)((1-0.8)*255)=50（浮点截断），y=(int)((1-0.8*0.4)*255)=173
        int color = table.grassColor(biome(0.8, 0.4, "none"));
        assertThat(color).isEqualTo((50 << 8) | 173);
    }

    @Test
    void foliageSkipsDownfallTimesTemperature() {
        BiomeColorTable table = new BiomeColorTable(new int[0], coordinateColormap());
        // 树叶：x=50, y=(int)((1-0.4)*255)=153（不含 downfall*temp）
        int color = table.foliageColor(biome(0.8, 0.4, "none"));
        assertThat(color).isEqualTo((50 << 8) | 153);
    }

    @Test
    void explicitEffectsColorWinsOverColormap() {
        BiomeColorTable table = new BiomeColorTable(coordinateColormap(), coordinateColormap());
        BiomeEffects badlands = new BiomeEffects(2.0, 0.0, 0x90814D, 0x9E814D, "none", null);
        assertThat(table.grassColor(badlands)).isEqualTo(0x90814D);
        assertThat(table.foliageColor(badlands)).isEqualTo(0x9E814D);
    }

    @Test
    void darkForestModifierBlendsWithFixedGreen() {
        BiomeColorTable table = new BiomeColorTable(coordinateColormap(), new int[0]);
        int sampled = table.grassColor(biome(0.8, 0.4, "none"));
        int blended = ((sampled & 0xFEFEFE) + 0x28340A) >> 1;
        assertThat(table.grassColor(biome(0.8, 0.4, "dark_forest"))).isEqualTo(blended);
    }

    @Test
    void swampModifierReturnsConstant() {
        BiomeColorTable table = new BiomeColorTable(coordinateColormap(), new int[0]);
        assertThat(table.grassColor(biome(0.8, 0.9, "swamp"))).isEqualTo(0x6A7039);
        // 树叶不受 swamp 草修饰器影响
        assertThat(table.foliageColor(biome(0.8, 0.9, "swamp")))
                .isEqualTo(table.foliageColor(biome(0.8, 0.9, "none")));
    }

    @Test
    void missingColormapFallsBack() {
        BiomeColorTable table = new BiomeColorTable(new int[0], null);
        assertThat(table.grassColor(biome(0.8, 0.4, "none")))
                .isEqualTo(BiomeColorTable.FALLBACK_GRASS);
        assertThat(table.foliageColor(biome(0.8, 0.4, "none")))
                .isEqualTo(BiomeColorTable.FALLBACK_FOLIAGE);
    }

    @Test
    void waterColorUsesEffectsOrDefault() {
        BiomeColorTable table = new BiomeColorTable(new int[0], new int[0]);
        BiomeEffects swamp = new BiomeEffects(0.8, 0.9, null, null, "swamp", 0x617B64);
        assertThat(table.waterColor(swamp)).isEqualTo(0x617B64);
        assertThat(table.waterColor(BiomeEffects.DEFAULT)).isEqualTo(BiomeColorTable.FALLBACK_WATER);
    }

    @Test
    void temperatureClampsToColormapRange() {
        BiomeColorTable table = new BiomeColorTable(coordinateColormap(), new int[0]);
        // 超界温度钳制到 [0,1]：temp=2.0 → x=0
        int color = table.grassColor(biome(2.0, 0.0, "none"));
        assertThat(color).isEqualTo((0 << 8) | 255);
    }
}
