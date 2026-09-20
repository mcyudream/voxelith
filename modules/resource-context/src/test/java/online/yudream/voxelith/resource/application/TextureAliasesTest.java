package online.yudream.voxelith.resource.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨版本贴图改名兜底：旧名要能找到新名，新名也要能找到旧名，
 * 且不能对无关贴图瞎猜（猜错会把别的方块的颜色贴上来，比品红更难查）。
 */
class TextureAliasesTest {

    @Test
    @DisplayName("定名改名的常见贴图双向可查")
    void knownRenamesBothWays() {
        assertThat(TextureAliases.candidates("minecraft:block/grass"))
                .contains("minecraft:block/short_grass");
        assertThat(TextureAliases.candidates("minecraft:block/short_grass"))
                .contains("minecraft:block/grass");

        assertThat(TextureAliases.candidates("minecraft:block/grass_path_top"))
                .contains("minecraft:block/dirt_path_top");
        assertThat(TextureAliases.candidates("minecraft:block/dirt_path_side"))
                .contains("minecraft:block/grass_path_side");

        assertThat(TextureAliases.candidates("minecraft:block/grass_block_side"))
                .contains("minecraft:block/grass_side");
        assertThat(TextureAliases.candidates("minecraft:block/stonebrick"))
                .contains("minecraft:block/stone_bricks");
        assertThat(TextureAliases.candidates("minecraft:block/nether_brick"))
                .contains("minecraft:block/nether_bricks");
        assertThat(TextureAliases.candidates("minecraft:block/log_big_oak"))
                .contains("minecraft:block/dark_oak_log");
    }

    @Test
    @DisplayName("1.13 flattening 的颜色族：前缀 ↔ 后缀互换")
    void colorFamiliesBothWays() {
        assertThat(TextureAliases.candidates("minecraft:block/red_wool"))
                .contains("minecraft:block/wool_colored_red");
        assertThat(TextureAliases.candidates("minecraft:block/wool_colored_light_blue"))
                .contains("minecraft:block/light_blue_wool");
        assertThat(TextureAliases.candidates("minecraft:block/orange_terracotta"))
                .contains("minecraft:block/hardened_clay_stained_orange");
        assertThat(TextureAliases.candidates("minecraft:block/hardened_clay_stained_black"))
                .contains("minecraft:block/black_terracotta");
        assertThat(TextureAliases.candidates("minecraft:block/stained_glass_pane_green"))
                .contains("minecraft:block/green_stained_glass_pane");
        assertThat(TextureAliases.candidates("minecraft:block/lime_carpet"))
                .contains("minecraft:block/carpet_lime");
    }

    @Test
    @DisplayName("木材族：planks/log 前后缀互换（含 dark_oak ↔ big_oak）")
    void woodFamilies() {
        assertThat(TextureAliases.candidates("minecraft:block/spruce_planks"))
                .contains("minecraft:block/planks_spruce");
        assertThat(TextureAliases.candidates("minecraft:block/planks_jungle"))
                .contains("minecraft:block/jungle_planks");
        assertThat(TextureAliases.candidates("minecraft:block/log_oak"))
                .contains("minecraft:block/oak_log");
        assertThat(TextureAliases.candidates("minecraft:block/dark_oak_log_top"))
                .contains("minecraft:block/log_big_oak_top");
    }

    @Test
    @DisplayName("候选里不含原名，且不误伤无关贴图 / mod 命名空间")
    void noFalsePositives() {
        assertThat(TextureAliases.candidates("minecraft:block/grass"))
                .doesNotContain("minecraft:block/grass");
        assertThat(TextureAliases.candidates("minecraft:block/stone")).isEmpty();
        assertThat(TextureAliases.candidates("minecraft:block/dirt")).isEmpty();
        // mod 的命名由作者决定，一律不猜
        assertThat(TextureAliases.candidates("mymod:block/grass")).isEmpty();
        assertThat(TextureAliases.candidates(null)).isEmpty();
    }

    @Test
    @DisplayName("候选有序且去重（同一 id 不会出现两次）")
    void candidatesAreOrderedAndUnique() {
        List<String> candidates = TextureAliases.candidates("minecraft:block/grass");
        assertThat(candidates).doesNotHaveDuplicates();
        assertThat(candidates.getFirst()).isEqualTo("minecraft:block/short_grass");
    }
}
