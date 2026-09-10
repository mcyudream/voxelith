package online.yudream.voxelith.resource.infrastructure.parse;

import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.resource.domain.model.StateCondition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GsonBlockstateParserTest {

    private final GsonBlockstateParser parser = new GsonBlockstateParser();

    @Test
    void parsesSimpleVariants() {
        var definition = parser.parse("""
                {"variants": {"": {"model": "minecraft:block/stone"}}}
                """);
        assertThat(definition).isInstanceOf(BlockstateDefinition.Variants.class);
        var variants = (BlockstateDefinition.Variants) definition;
        assertThat(variants.variants()).containsKey("");
        assertThat(variants.variants().get("").getFirst().model().toString())
                .isEqualTo("minecraft:block/stone");
    }

    @Test
    void parsesVariantWithRotationWeightAndList() {
        var definition = parser.parse("""
                {"variants": {"facing=north": [
                  {"model": "minecraft:block/a", "y": 90, "uvlock": true, "weight": 3},
                  {"model": "minecraft:block/b"}
                ]}}
                """);
        var list = ((BlockstateDefinition.Variants) definition).variants().get("facing=north");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).y()).isEqualTo(90);
        assertThat(list.get(0).uvLock()).isTrue();
        assertThat(list.get(0).weight()).isEqualTo(3);
        assertThat(list.get(1).x()).isZero();
        assertThat(list.get(1).weight()).isEqualTo(1);
    }

    @Test
    void parsesMultipartWithOrAndPipeValues() {
        var definition = parser.parse("""
                {"multipart": [
                  {"apply": {"model": "minecraft:block/base"}},
                  {"when": {"OR": [{"north": "true"}, {"north": "side|up"}]},
                   "apply": [{"model": "minecraft:block/arm"}]}
                ]}
                """);
        assertThat(definition).isInstanceOf(BlockstateDefinition.Multipart.class);
        var cases = ((BlockstateDefinition.Multipart) definition).cases();
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).when()).isSameAs(StateCondition.ALWAYS);
        assertThat(cases.get(1).when().matches(Map.of("north", "up"))).isTrue();
        assertThat(cases.get(1).when().matches(Map.of("north", "none"))).isFalse();
    }

    @Test
    void rejectsJsonWithoutVariantsOrMultipart() {
        assertThatThrownBy(() -> parser.parse("{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
