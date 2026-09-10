package online.yudream.voxelith.resource.infrastructure.parse;

import online.yudream.voxelith.resource.domain.model.ModelDefinition;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GsonModelParserTest {

    private final GsonModelParser parser = new GsonModelParser();

    @Test
    void parsesCubeAllChild() {
        ModelDefinition def = parser.parse(Identifier.minecraft("block/stone"), """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": {"all": "minecraft:block/stone"}
                }
                """);
        assertThat(def.parent().toString()).isEqualTo("minecraft:block/cube_all");
        assertThat(def.textures()).containsEntry("all", "minecraft:block/stone");
        assertThat(def.hasElements()).isFalse();
    }

    @Test
    void parsesElementsFacesRotationAndTint() {
        ModelDefinition def = parser.parse(Identifier.minecraft("block/x"), """
                {
                  "ambientocclusion": false,
                  "elements": [{
                    "from": [0, 0, 0], "to": [16, 8, 16],
                    "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                    "shade": false,
                    "faces": {
                      "up": {"uv": [0, 0, 16, 16], "texture": "#top", "cullface": "up", "rotation": 90},
                      "north": {"texture": "#side", "tintindex": 0}
                    }
                  }]
                }
                """);
        assertThat(def.ambientOcclusion()).isFalse();
        var element = def.elements().getFirst();
        assertThat(element.from()).containsExactly(0f, 0f, 0f);
        assertThat(element.to()).containsExactly(16f, 8f, 16f);
        assertThat(element.rotation().angle()).isEqualTo(45f);
        assertThat(element.shade()).isFalse();
        assertThat(element.faces()).containsKeys(Direction.UP, Direction.NORTH);
        assertThat(element.faces().get(Direction.UP).cullface()).isEqualTo(Direction.UP);
        assertThat(element.faces().get(Direction.UP).rotation()).isEqualTo(90);
        assertThat(element.faces().get(Direction.NORTH).uv()).isNull();
        assertThat(element.faces().get(Direction.NORTH).tintIndex()).isEqualTo(0);
        assertThat(element.faces().get(Direction.UP).tintIndex()).isEqualTo(-1);
    }
}
