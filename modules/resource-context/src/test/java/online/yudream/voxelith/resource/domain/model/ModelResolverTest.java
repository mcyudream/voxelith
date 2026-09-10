package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.resource.domain.pack.InMemoryResourcePack;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.infrastructure.parse.GsonModelParser;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResolverTest {

    private static final Identifier CUBE_ALL = Identifier.minecraft("block/cube_all");
    private static final Identifier CUBE = Identifier.minecraft("block/cube");
    private static final Identifier BLOCK = Identifier.minecraft("block/block");
    private static final Identifier STONE = Identifier.minecraft("block/stone");

    private InMemoryResourcePack vanillaChain() {
        return new InMemoryResourcePack("vanilla", 0)
                .putModel(BLOCK, """
                        {"textures": {}, "elements": []}
                        """)
                .putModel(CUBE, """
                        {
                          "parent": "minecraft:block/block",
                          "textures": {"particle": "#down"},
                          "elements": [{
                            "from": [0,0,0], "to": [16,16,16],
                            "faces": {
                              "down": {"texture": "#down", "cullface": "down"},
                              "up": {"texture": "#up", "cullface": "up"},
                              "north": {"texture": "#north", "cullface": "north"}
                            }
                          }]
                        }
                        """)
                .putModel(CUBE_ALL, """
                        {
                          "parent": "minecraft:block/cube",
                          "textures": {"down": "#all", "up": "#all", "north": "#all"}
                        }
                        """)
                .putModel(STONE, """
                        {
                          "parent": "minecraft:block/cube_all",
                          "textures": {"all": "minecraft:block/stone"}
                        }
                        """);
    }

    @Test
    void resolvesParentChainAndTextureReferences() {
        ModelResolver resolver = new ModelResolver(
                PackStack.of(List.of(vanillaChain())), new GsonModelParser());

        ResolvedModel stone = resolver.resolve(STONE);

        assertThat(stone.elements()).hasSize(1);
        assertThat(stone.textures()).containsEntry("all", Identifier.minecraft("block/stone"));
        // "#all" 链式引用穿透到最终贴图
        assertThat(stone.textures()).containsEntry("down", Identifier.minecraft("block/stone"));
        assertThat(stone.textures()).containsEntry("up", Identifier.minecraft("block/stone"));
        assertThat(stone.textures()).containsEntry("particle", Identifier.minecraft("block/stone"));
        assertThat(stone.resolveTexture("#north")).contains(Identifier.minecraft("block/stone"));
    }

    @Test
    void childElementsOverrideParent() {
        InMemoryResourcePack pack = vanillaChain()
                .putModel(Identifier.minecraft("block/custom"), """
                        {
                          "parent": "minecraft:block/cube",
                          "elements": [{"from": [1,1,1], "to": [2,2,2], "faces": {}}]
                        }
                        """);
        ModelResolver resolver = new ModelResolver(PackStack.of(List.of(pack)), new GsonModelParser());

        ResolvedModel custom = resolver.resolve(Identifier.minecraft("block/custom"));

        assertThat(custom.elements()).hasSize(1);
        assertThat(custom.elements().getFirst().from()).containsExactly(1f, 1f, 1f);
    }

    @Test
    void detectsInheritanceCycle() {
        InMemoryResourcePack pack = new InMemoryResourcePack("bad", 0)
                .putModel(Identifier.minecraft("block/a"), "{\"parent\": \"minecraft:block/b\"}")
                .putModel(Identifier.minecraft("block/b"), "{\"parent\": \"minecraft:block/a\"}");
        ModelResolver resolver = new ModelResolver(PackStack.of(List.of(pack)), new GsonModelParser());

        assertThatThrownBy(() -> resolver.resolve(Identifier.minecraft("block/a")))
                .isInstanceOf(ModelResolutionException.class)
                .hasMessageContaining("循环");
    }

    @Test
    void throwsOnMissingModel() {
        ModelResolver resolver = new ModelResolver(
                PackStack.of(List.of(new InMemoryResourcePack("empty", 0))), new GsonModelParser());

        assertThatThrownBy(() -> resolver.resolve(Identifier.minecraft("block/void")))
                .isInstanceOf(ModelResolutionException.class);
    }
}
