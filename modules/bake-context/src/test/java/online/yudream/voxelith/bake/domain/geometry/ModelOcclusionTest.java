package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelOcclusionTest {

    @Test
    void fullCubeOccludesAllDirections() {
        ModelData cube = ModelGeometryBakerTest.cubeAll("minecraft:block/stone");
        for (Direction dir : Direction.values()) {
            assertThat(ModelOcclusion.occludes("minecraft:stone", cube, dir)).isTrue();
        }
    }

    @Test
    void glassNeverOccludes() {
        ModelData cube = ModelGeometryBakerTest.cubeAll("minecraft:block/glass");
        assertThat(ModelOcclusion.occludes("minecraft:glass", cube, Direction.UP)).isFalse();
    }

    @Test
    void slabDoesNotOcclude() {
        Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
        for (String dir : List.of("down", "up", "north", "south", "west", "east")) {
            faces.put(dir, new ModelData.FaceData(null, "#side", null, 0, -1));
        }
        ModelData slab = new ModelData(
                Map.of("side", "minecraft:block/stone_slab_side"),
                List.of(new ModelData.ElementData(
                        new float[]{0, 0, 0}, new float[]{16, 8, 16}, null, true, faces)),
                true);
        assertThat(ModelOcclusion.occludes("minecraft:stone_slab", slab, Direction.UP)).isFalse();
        assertThat(ModelOcclusion.occludes("minecraft:stone_slab", slab, Direction.DOWN)).isFalse();
    }
}
