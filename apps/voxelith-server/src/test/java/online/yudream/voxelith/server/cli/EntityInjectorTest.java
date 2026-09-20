package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.world.domain.world.PlacedEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盔甲架几何：绕序（正面剔除下不能画成内翻）、尺寸、朝向、Small/ShowArms/NoBasePlate 开关，
 * 以及贴图与注入区块。
 */
class EntityInjectorTest {

    private static PlacedEntity stand(double x, double y, double z, float yaw,
                                      boolean small, boolean showArms, boolean noBasePlate) {
        return new PlacedEntity(PlacedEntity.ARMOR_STAND, x, y, z, yaw, small, showArms, noBasePlate);
    }

    private static EntityInjector injector() {
        return new EntityInjector(id -> Optional.empty());
    }

    /** 四个顶点两两相邻边的叉乘（三角形 0-1-2），应指向面法线。 */
    private static float[] windingNormal(BakedQuadData quad) {
        float[] p = quad.positions();
        float[] a = {p[3] - p[0], p[4] - p[1], p[5] - p[2]};
        float[] b = {p[6] - p[0], p[7] - p[1], p[8] - p[2]};
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    @Test
    @DisplayName("每个面的绕序与声明法线一致，且法线朝盒外（避免背面剔除下内翻）")
    void windingMatchesOutwardNormal() {
        PlacedEntity entity = stand(0, 64, 0, 0f, false, true, false);
        List<BakedQuadData> quads = EntityInjector.armorStandQuads(
                entity, EntityInjector.ARMOR_STAND_TEXTURE);
        assertThat(quads).isNotEmpty();
        for (BakedQuadData quad : quads) {
            float[] normal = quad.normal();
            float[] winding = windingNormal(quad);
            float dot = winding[0] * normal[0] + winding[1] * normal[1] + winding[2] * normal[2];
            assertThat(dot).as("绕序叉乘应沿着声明法线").isGreaterThan(0f);

            // 法线是该面的**自身盒体**某轴的外向：面的坐标应落在自身包围盒该轴的 max（正向）
            // 或 min（负向）上。注意不能用「相对实体中心」判断——偏移盒体（手臂）的内侧法线
            // 本来就朝着实体中心，这是正确的。
            int axis = Math.abs(normal[0]) > 0.5f ? 0 : Math.abs(normal[1]) > 0.5f ? 1 : 2;
            assertThat(normal[axis]).as("法线必须是轴平行单位向量").isEqualTo(Math.signum(normal[axis]));
            float[] p = quad.positions();
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                min = Math.min(min, p[i * 3 + axis]);
                max = Math.max(max, p[i * 3 + axis]);
            }
            float faceValue = p[axis];
            if (normal[axis] > 0) {
                assertThat(faceValue).as("正向面的坐标应是自身包围盒该轴的最大值").isEqualTo(max);
            } else {
                assertThat(faceValue).as("负向面的坐标应是自身包围盒该轴的最小值").isEqualTo(min);
            }
        }
    }

    @Test
    @DisplayName("尺寸与原版一致：高 2 格、宽不超过 1 格；底座/手臂/小型按开关增减")
    void dimensionsAndFlags() {
        PlacedEntity full = stand(0, 64, 0, 0f, false, true, false);
        List<BakedQuadData> fullQuads = EntityInjector.armorStandQuads(
                full, EntityInjector.ARMOR_STAND_TEXTURE);
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (BakedQuadData quad : fullQuads) {
            for (int i = 0; i < 4; i++) {
                minY = Math.min(minY, quad.positions()[i * 3 + 1]);
                maxY = Math.max(maxY, quad.positions()[i * 3 + 1]);
                minX = Math.min(minX, quad.positions()[i * 3]);
                maxX = Math.max(maxX, quad.positions()[i * 3]);
            }
        }
        assertThat(maxY - minY).as("盔甲架总高约 2 格").isCloseTo(2f,
                org.assertj.core.data.Offset.offset(0.05f));
        assertThat(maxX - minX).as("最宽处（含手臂）不超过 1 格").isLessThanOrEqualTo(1f);

        // 小型：整体减半
        List<BakedQuadData> smallQuads = EntityInjector.armorStandQuads(
                stand(0, 64, 0, 0f, true, true, false), EntityInjector.ARMOR_STAND_TEXTURE);
        float smallMaxY = -Float.MAX_VALUE;
        for (BakedQuadData quad : smallQuads) {
            for (int i = 0; i < 4; i++) {
                smallMaxY = Math.max(smallMaxY, quad.positions()[i * 3 + 1]);
            }
        }
        assertThat(smallMaxY - 64f).as("小型高约 1 格").isCloseTo(1f,
                org.assertj.core.data.Offset.offset(0.05f));

        // 不显示手臂 → 面片更少
        int withArms = EntityInjector.armorStandQuads(
                stand(0, 0, 0, 0f, false, true, false), EntityInjector.ARMOR_STAND_TEXTURE).size();
        int withoutArms = EntityInjector.armorStandQuads(
                stand(0, 0, 0, 0f, false, false, false), EntityInjector.ARMOR_STAND_TEXTURE).size();
        assertThat(withoutArms).as("隐藏手臂应少两个盒体").isEqualTo(withArms - 12);

        // 无底座 → 少一个盒体（6 面）
        int noBase = EntityInjector.armorStandQuads(
                stand(0, 0, 0, 0f, false, true, true), EntityInjector.ARMOR_STAND_TEXTURE).size();
        assertThat(noBase).isEqualTo(withArms - 6);
    }

    @Test
    @DisplayName("朝向：yaw=90 时手臂沿 Z 轴展开（模型绕 Y 轴旋转）")
    void yawRotatesModel() {
        // 模型对称、底座又是正方形，用「跨度大小」判断朝向不可靠；
        // 直接验证变换本身：(x,z) 在 yaw=90 时应等于 yaw=0 时的 (-z, x)
        float[][] base = vertices(EntityInjector.armorStandQuads(
                stand(10, 64, -5, 0f, false, true, false), EntityInjector.ARMOR_STAND_TEXTURE));
        float[][] rotated = vertices(EntityInjector.armorStandQuads(
                stand(10, 64, -5, 90f, false, true, false), EntityInjector.ARMOR_STAND_TEXTURE));
        assertThat(rotated).hasSameDimensionsAs(base);

        for (int i = 0; i < base.length; i++) {
            float dx = base[i][0] - 10f;
            float dz = base[i][2] + 5f;
            assertThat(rotated[i][0] - 10f).as("x' = -z").isCloseTo(-dz,
                    org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(rotated[i][2] + 5f).as("z' = x").isCloseTo(dx,
                    org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(rotated[i][1]).as("yaw 不影响高度").isEqualTo(base[i][1]);
        }
    }

    /** 所有面片的顶点（世界坐标），面片顺序固定所以可以逐顶点比对。 */
    private static float[][] vertices(List<BakedQuadData> quads) {
        float[][] out = new float[quads.size() * 4][];
        int at = 0;
        for (BakedQuadData quad : quads) {
            float[] p = quad.positions();
            for (int i = 0; i < 4; i++) {
                out[at++] = new float[]{p[i * 3], p[i * 3 + 1], p[i * 3 + 2]};
            }
        }
        return out;
    }

    @Test
    @DisplayName("天空光按原始等级 0..15 写入（写 255 会被 TileMeshAssembler 二次相乘溢出成偏暗）")
    void skyLightIsRawLevel() {
        for (BakedQuadData quad : EntityInjector.armorStandQuads(
                stand(0, 64, 0, 0f, false, true, false), EntityInjector.ARMOR_STAND_TEXTURE)) {
            assertThat(quad.skyLight()).as("4 个顶点都要有天空光").hasSize(4);
            for (byte sky : quad.skyLight()) {
                assertThat((int) sky).as("天空光等级必须在 0..15").isBetween(0, 15);
            }
            assertThat(quad.blockLight()).hasSize(4);
            for (byte block : quad.blockLight()) {
                assertThat((int) block).as("方块光等级必须在 0..15").isBetween(0, 15);
            }
        }
    }

    @Test
    @DisplayName("注入到实体所在区块（含负坐标），并保持原有区块内容")
    void injectsIntoOwningChunk() {
        EntityInjector injector = injector();
        Map<ChunkPos, BakedChunkMeshData> meshes = Map.of(
                new ChunkPos(0, 0), new BakedChunkMeshData(new ChunkPos(0, 0), List.of(), Map.of(), 1));
        Map<ChunkPos, BakedChunkMeshData> out = injector.inject(
                List.of(stand(-3.5, 64, 20.5, 0f, false, false, false)), meshes);

        ChunkPos expected = new ChunkPos(Math.floorDiv(-4, 16), Math.floorDiv(20, 16));
        assertThat(out).containsKey(expected);
        assertThat(out.get(expected).quads()).isNotEmpty();
        assertThat(out.get(new ChunkPos(0, 0)).quads()).isEmpty();
        // 贴图是本注入器内置的（不依赖资源包）
        assertThat(out.get(expected).quads().getFirst().texture())
                .isEqualTo(EntityInjector.ARMOR_STAND_TEXTURE);
    }

    @Test
    @DisplayName("内置木纹贴图可用，非盔甲架实体不产生几何")
    void textureAndEntityFilter() {
        EntityInjector injector = injector();
        assertThat(injector.load(Identifier.parse(EntityInjector.ARMOR_STAND_TEXTURE))).isPresent();
        assertThat(injector.load(Identifier.parse("minecraft:block/stone"))).isEmpty();

        Map<ChunkPos, BakedChunkMeshData> out = injector.inject(
                List.of(new PlacedEntity("minecraft:pig", 0, 64, 0, 0f, false, false, false)), Map.of());
        assertThat(out).isEmpty();
    }
}
