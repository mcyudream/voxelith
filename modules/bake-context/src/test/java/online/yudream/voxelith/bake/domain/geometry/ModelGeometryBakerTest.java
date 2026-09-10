package online.yudream.voxelith.bake.domain.geometry;

import online.yudream.voxelith.resource.application.dto.ModelData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModelGeometryBakerTest {

    private final ModelGeometryBaker baker = new ModelGeometryBaker();

    @Test
    void cubeAllBakesSixOutwardQuads() {
        ModelData cube = cubeAll("minecraft:block/stone");

        List<Quad> quads = baker.bake(cube, 0, 0);

        assertThat(quads).hasSize(6);
        for (Quad quad : quads) {
            assertThat(quad.texture()).isEqualTo("minecraft:block/stone");
            assertThat(quad.cullface()).isEqualTo(quad.face());
            // 绕序校验：相邻两边叉积应与法向同向（外向逆时针）
            float[] p = quad.positions();
            float[] e1 = {p[3] - p[0], p[4] - p[1], p[5] - p[2]};
            float[] e2 = {p[6] - p[3], p[7] - p[4], p[8] - p[5]};
            float[] cross = {
                    e1[1] * e2[2] - e1[2] * e2[1],
                    e1[2] * e2[0] - e1[0] * e2[2],
                    e1[0] * e2[1] - e1[1] * e2[0]};
            float dot = cross[0] * quad.normal()[0] + cross[1] * quad.normal()[1] + cross[2] * quad.normal()[2];
            assertThat(dot).as("face %s winding", quad.face()).isPositive();
        }
        // up 面四角 y=16，法向 +y
        Quad up = quads.stream().filter(q -> q.face().equals("up")).findFirst().orElseThrow();
        assertThat(up.normal()).containsExactly(0f, 1f, 0f);
        for (int i = 1; i < 12; i += 3) {
            assertThat(up.positions()[i]).isEqualTo(16f);
        }
    }

    @Test
    void defaultUvMatchesVanillaProjectionTable() {
        ModelData cube = cubeAll("minecraft:block/stone");

        List<Quad> quads = baker.bake(cube, 0, 0);

        // up 面：u=x, v=z；v0=(x1,y2,z2) → (0,16)
        Quad up = quads.stream().filter(q -> q.face().equals("up")).findFirst().orElseThrow();
        assertThat(up.uvs()[0]).isEqualTo(0f);
        assertThat(up.uvs()[1]).isEqualTo(16f);
        // south 面：u=x, v=16-y；v0=(x1,y1,z2) 底左角 → (0,16)，v2=(x2,y2,z2) 顶右角 → (16,0)
        Quad south = quads.stream().filter(q -> q.face().equals("south")).findFirst().orElseThrow();
        assertThat(south.uvs()[0]).isEqualTo(0f);
        assertThat(south.uvs()[1]).isEqualTo(16f);
        assertThat(south.uvs()[4]).isEqualTo(16f);
        assertThat(south.uvs()[5]).isEqualTo(0f);
    }

    @Test
    void variantYRotationTurnsNorthFaceToEast() {
        ModelData cube = cubeAll("minecraft:block/stone");

        List<Quad> quads = baker.bake(cube, 0, 90);

        Quad rotated = quads.stream().filter(q -> q.face().equals("north")).findFirst().orElseThrow();
        assertThat(rotated.cullface()).isEqualTo("east");
        assertThat((double) rotated.normal()[0]).isCloseTo(1.0, within(1e-6));
        assertThat((double) rotated.normal()[2]).isCloseTo(0.0, within(1e-6));
        // 顶点仍在 0..16 方块范围内
        for (float v : rotated.positions()) {
            assertThat(v).isBetween(-1e-5f, 16.00001f);
        }
    }

    @Test
    void elementRotationAppliesRescaleAroundOrigin() {
        // 45° 斜面元素（cross 模型式）：绕 y 旋转 45° + rescale
        Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
        faces.put("north", new ModelData.FaceData(null, "#cross", null, 0, -1));
        faces.put("south", new ModelData.FaceData(null, "#cross", null, 0, -1));
        ModelData.ElementData element = new ModelData.ElementData(
                new float[]{0, 0, 8 - 5.66f}, new float[]{16, 16, 8 + 5.66f},
                new ModelData.RotationData(new float[]{8, 8, 8}, "y", 45, true),
                false, faces);
        ModelData model = new ModelData(Map.of("cross", "minecraft:block/oak_sapling"), List.of(element), false);

        List<Quad> quads = baker.bake(model, 0, 0);

        assertThat(quads).hasSize(2);
        // rescale 后 z 跨度应放大为 11.32/ cos(45°) ≈ 16 → 覆盖整个方块宽度
        Quad quad = quads.getFirst();
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 2; i < 12; i += 3) {
            minZ = Math.min(minZ, quad.positions()[i]);
            maxZ = Math.max(maxZ, quad.positions()[i]);
        }
        assertThat((double) (maxZ - minZ)).isCloseTo(16.0, within(0.1));
    }

    /** 满方块 cube_all 风格模型：六面同贴图 + 各向 cullface。 */
    static ModelData cubeAll(String texture) {
        Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
        for (String dir : List.of("down", "up", "north", "south", "west", "east")) {
            faces.put(dir, new ModelData.FaceData(null, "#all", dir, 0, -1));
        }
        ModelData.ElementData element = new ModelData.ElementData(
                new float[]{0, 0, 0}, new float[]{16, 16, 16}, null, true, faces);
        return new ModelData(Map.of("all", texture), List.of(element), true);
    }
}
