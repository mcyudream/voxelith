package online.yudream.voxelith.resource.infrastructure.biome;

import online.yudream.voxelith.resource.domain.biome.BiomeEffects;
import online.yudream.voxelith.resource.domain.pack.InMemoryResourcePack;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 群系 effects 色值解析回归：1.21.x 起色值可以是 "#rrggbb" 字符串（旧版为裸整数），
 * 色值格式不兼容绝不能让 bake 渲染整体失败。
 */
class PackBiomeSourceTest {

    private InMemoryResourcePack pack;

    @BeforeEach
    void setUp() {
        pack = new InMemoryResourcePack("test", 1);
    }

    private PackBiomeSource source() {
        return new PackBiomeSource(PackStack.of(List.of(pack)));
    }

    private void putBiome(String name, String effectsJson) {
        pack.putRaw("data/minecraft/worldgen/biome/" + name + ".json",
                "{\"temperature\":0.8,\"downfall\":0.4,\"effects\":" + effectsJson + "}");
    }

    @Test
    @DisplayName("旧版裸整数形态照常解析（回归保护）")
    void parsesLegacyIntegerColors() {
        putBiome("legacy", "{\"water_color\":4159204}");

        BiomeEffects effects = source().effects("minecraft:legacy").orElseThrow();

        assertThat(effects.waterColor()).isEqualTo(4159204);
    }

    @Test
    @DisplayName("新版 \"#rrggbb\" 字符串形态解析出同一色值（1.21.11 现场炸点）")
    void parsesHexStringColors() {
        putBiome("modern", "{\"grass_color\":\"#7cbd6b\",\"foliage_color\":\"#aeaa4e\","
                + "\"water_color\":\"#3f76e4\",\"grass_color_modifier\":\"dark_forest\"}");

        BiomeEffects effects = source().effects("minecraft:modern").orElseThrow();

        assertThat(effects.grassColor()).isEqualTo(0x7CBD6B);
        assertThat(effects.foliageColor()).isEqualTo(0xAEAA4E);
        assertThat(effects.waterColor()).isEqualTo(0x3F76E4);
        assertThat(effects.waterColor()).isEqualTo(4159204); // 与旧版裸整数等价
        assertThat(effects.grassColorModifier()).isEqualTo("dark_forest");
    }

    @Test
    @DisplayName("非法色值退 null 走默认色，不抛异常")
    void invalidColorFallsBackToNull() {
        putBiome("broken", "{\"water_color\":\"not-a-color\",\"grass_color\":{}}");

        assertThatCode(() -> source().effects("minecraft:broken")).doesNotThrowAnyException();

        BiomeEffects effects = source().effects("minecraft:broken").orElseThrow();
        assertThat(effects.waterColor()).isNull();
        assertThat(effects.grassColor()).isNull();
    }
}
