package online.yudream.voxelith.lod.domain.heightfield;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeightfieldTest {

    @Test
    void highestSampleWinsAndCarriesItsColor() {
        Heightfield field = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 64, 0.5f, 0x112233),
                new LodSample(1.5f, 70, 1.5f, 0xAABBCC),
                new LodSample(0.25f, 60, 0.25f, 0x000000)), 2);

        assertThat(field.topY(0, 0)).isEqualTo(70f);
        assertThat(field.rgb(0, 0)).isEqualTo(0xAABBCC);
    }

    @Test
    void negativeCoordinatesAlignByFloorDiv() {
        Heightfield field = Heightfield.fromSamples(List.of(
                new LodSample(-0.5f, 64, -0.5f, 1),
                new LodSample(-2.5f, 70, -2.5f, 2)), 2);

        // 世界 x∈[-2,0) 属柱 -1，x∈[-4,-2) 属柱 -2
        assertThat(field.topY(-1, -1)).isEqualTo(64f);
        assertThat(field.topY(-2, -2)).isEqualTo(70f);
        assertThat(field.minTileX(32)).isEqualTo(-1);
        assertThat(field.maxTileX(32)).isEqualTo(-1);
    }

    @Test
    void aggregateTakesHighestChildAndItsColor() {
        Heightfield child = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 60, 0.5f, 0x111111),
                new LodSample(2.5f, 66, 0.5f, 0x222222),
                new LodSample(0.5f, 64, 2.5f, 0x333333)), 2);
        Heightfield parent = child.aggregate();

        assertThat(parent.footprint()).isEqualTo(4);
        // 父柱 (0,0) 覆盖世界 [0,4)²，最高子柱 66
        assertThat(parent.topY(0, 0)).isEqualTo(66f);
        assertThat(parent.rgb(0, 0)).isEqualTo(0x222222);
        assertThat(parent.floorY()).isEqualTo(66f);
    }

    @Test
    void aggregateSkipsEmptyChildren() {
        Heightfield child = Heightfield.fromSamples(List.of(
                new LodSample(2.5f, 64, 2.5f, 0x444444)), 2);
        Heightfield parent = child.aggregate();

        assertThat(parent.topY(0, 0)).isEqualTo(64f);
        assertThat(parent.rgb(0, 0)).isEqualTo(0x444444);
    }

    @Test
    void mergeUnionAndOtherOverwritesOverlap() {
        Heightfield left = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 10, 0.5f, 0x111111),
                new LodSample(2.5f, 20, 0.5f, 0x222222)), 2);
        Heightfield right = Heightfield.fromSamples(List.of(
                new LodSample(2.5f, 99, 0.5f, 0x999999),
                new LodSample(4.5f, 30, 0.5f, 0x333333)), 2);
        Heightfield merged = left.merge(right);
        assertThat(merged.topY(0, 0)).isEqualTo(10f);
        assertThat(merged.topY(1, 0)).isEqualTo(99f);
        assertThat(merged.rgb(1, 0)).isEqualTo(0x999999);
        assertThat(merged.topY(2, 0)).isEqualTo(30f);
    }

    @Test
    void clearColumnsThenMergeReplacesRegion() {
        Heightfield world = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 10, 0.5f, 1),
                new LodSample(2.5f, 20, 0.5f, 2)), 2);
        int[] bounds = Heightfield.regionColumnBounds(0, 0, 2);
        assertThat(bounds[0]).isEqualTo(0);
        Heightfield cleared = world.clearColumns(1, 0, 1, 0);
        assertThat(cleared.topY(0, 0)).isEqualTo(10f);
        assertThat(cleared.topY(1, 0)).isNaN();
        Heightfield replacement = Heightfield.fromSamples(List.of(
                new LodSample(2.5f, 77, 0.5f, 7)), 2);
        Heightfield merged = cleared.merge(replacement);
        assertThat(merged.topY(1, 0)).isEqualTo(77f);
        assertThat(merged.rgb(1, 0)).isEqualTo(7);
    }

    @Test
    void restoreRoundTripPreservesGrid() {
        Heightfield original = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 64, 0.5f, 0xAABBCC)), 2);
        Heightfield restored = Heightfield.restore(
                original.footprint(), original.originX(), original.originZ(),
                original.width(), original.depth(), original.copyTopY(), original.copyRgb(),
                original.floorY());
        assertThat(restored.topY(0, 0)).isEqualTo(64f);
        assertThat(restored.rgb(0, 0)).isEqualTo(0xAABBCC);
        assertThat(restored.floorY()).isEqualTo(64f);
    }

    @Test
    void outOfGridColumnsReadAsEmpty() {
        Heightfield field = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 64, 0.5f, 1)), 2);
        assertThat(field.topY(5, 0)).isNaN();
        assertThat(field.topY(-1, 0)).isNaN();
        assertThat(field.topY(0, -1)).isNaN();
    }
}
