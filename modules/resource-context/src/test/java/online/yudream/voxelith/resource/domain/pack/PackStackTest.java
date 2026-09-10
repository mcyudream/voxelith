package online.yudream.voxelith.resource.domain.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackStackTest {

    @Test
    void higherPriorityPackOverridesLower() {
        InMemoryResourcePack vanilla = new InMemoryResourcePack("vanilla", 0)
                .putModel(Identifier.minecraft("block/stone"), "{\"parent\":\"x\"}");
        InMemoryResourcePack rp = new InMemoryResourcePack("rp", 1)
                .putModel(Identifier.minecraft("block/stone"), "{\"parent\":\"y\"}");

        PackStack stack = PackStack.of(List.of(vanilla, rp));

        assertThat(stack.model(Identifier.minecraft("block/stone")))
                .hasValueSatisfying(r -> assertThat(r.asUtf8()).contains("\"y\""));
    }

    @Test
    void fallsThroughToLowerPriorityWhenMissing() {
        InMemoryResourcePack vanilla = new InMemoryResourcePack("vanilla", 0)
                .putModel(Identifier.minecraft("block/stone"), "{}");
        InMemoryResourcePack rp = new InMemoryResourcePack("rp", 1);

        PackStack stack = PackStack.of(List.of(vanilla, rp));

        assertThat(stack.model(Identifier.minecraft("block/stone"))).isPresent();
    }

    @Test
    void unionsBlockListingAcrossPacks() {
        InMemoryResourcePack a = new InMemoryResourcePack("a", 0)
                .putBlockstate(Identifier.minecraft("stone"), "{}");
        InMemoryResourcePack b = new InMemoryResourcePack("b", 1)
                .putBlockstate(Identifier.parse("mymod:ore"), "{}");

        PackStack stack = PackStack.of(List.of(a, b));

        assertThat(stack.listBlocksWithBlockstate())
                .containsExactlyInAnyOrder(Identifier.minecraft("stone"), Identifier.parse("mymod:ore"));
    }
}
