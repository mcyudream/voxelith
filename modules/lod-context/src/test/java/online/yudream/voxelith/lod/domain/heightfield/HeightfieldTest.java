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
    void emptySamplesYieldEmptyField() {
        Heightfield field = Heightfield.fromSamples(List.of(), 2);
        assertThat(field.width()).isZero();
        assertThat(field.depth()).isZero();
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
