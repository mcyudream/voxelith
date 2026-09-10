package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockstateResolverTest {

    private final BlockstateResolver resolver = new BlockstateResolver();

    @Test
    void variantsMatchBySubsetAndPickMostSpecific() {
        var variants = new BlockstateDefinition.Variants(Map.of(
                "", List.of(new ModelVariant(Identifier.minecraft("block/default"), 0, 0, false, 1)),
                "facing=north", List.of(new ModelVariant(Identifier.minecraft("block/north"), 0, 0, false, 1)),
                "facing=north,lit=true", List.of(new ModelVariant(Identifier.minecraft("block/north_lit"), 0, 0, false, 1))));

        assertThat(resolver.select(variants, Map.of("facing", "north", "lit", "true"))
                .getFirst().getFirst().model().toString()).isEqualTo("minecraft:block/north_lit");
        assertThat(resolver.select(variants, Map.of("facing", "north", "lit", "false"))
                .getFirst().getFirst().model().toString()).isEqualTo("minecraft:block/north");
        assertThat(resolver.select(variants, Map.of("facing", "south", "lit", "true"))
                .getFirst().getFirst().model().toString()).isEqualTo("minecraft:block/default");
    }

    @Test
    void multipartAccumulatesAllMatchingCases() {
        var multipart = new BlockstateDefinition.Multipart(List.of(
                new MultipartCase(StateCondition.ALWAYS,
                        List.of(new ModelVariant(Identifier.minecraft("block/base"), 0, 0, false, 1))),
                new MultipartCase(new StateCondition.PropertySet(Map.of("north", java.util.Set.of("true"))),
                        List.of(new ModelVariant(Identifier.minecraft("block/arm_n"), 0, 0, false, 1)))));

        List<List<ModelVariant>> withNorth = resolver.select(multipart, Map.of("north", "true"));
        assertThat(withNorth).hasSize(2);

        List<List<ModelVariant>> withoutNorth = resolver.select(multipart, Map.of("north", "false"));
        assertThat(withoutNorth).hasSize(1);
    }
}
