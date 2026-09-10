package online.yudream.voxelith.orchestration.infrastructure.incremental;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegionIncrementalRenderAdapterTest {

    @Test
    void regionZeroMapsToFull32x32ChunkGridAnd16x16HiresTiles() {
        RegionPos region = new RegionPos(0, 0);
        List<ChunkPos> chunks = RegionIncrementalRenderAdapter.chunksOf(region);
        assertThat(chunks).hasSize(32 * 32);
        assertThat(chunks.getFirst()).isEqualTo(new ChunkPos(0, 0));
        assertThat(chunks.getLast()).isEqualTo(new ChunkPos(31, 31));

        List<TilePos> tiles = RegionIncrementalRenderAdapter.expectedHiresTiles(region);
        assertThat(tiles).hasSize(16 * 16);
        assertThat(tiles.getFirst()).isEqualTo(TilePos.hires(0, 0));
        assertThat(tiles.getLast()).isEqualTo(TilePos.hires(15, 15));
        assertThat(RegionIncrementalRenderAdapter.urlOf(TilePos.hires(0, 0)))
                .isEqualTo("tiles/hires/0/0.glb");
        assertThat(RegionIncrementalRenderAdapter.urlOf(new TilePos(2, 3, 4)))
                .isEqualTo("tiles/lod/2/3/4.glb");
    }

    @Test
    void negativeRegionUsesArithmeticShift() {
        RegionPos region = new RegionPos(-1, -1);
        List<ChunkPos> chunks = RegionIncrementalRenderAdapter.chunksOf(region);
        assertThat(chunks.getFirst()).isEqualTo(new ChunkPos(-32, -32));
        assertThat(chunks.getLast()).isEqualTo(new ChunkPos(-1, -1));
        List<TilePos> tiles = RegionIncrementalRenderAdapter.expectedHiresTiles(region);
        assertThat(tiles.getFirst()).isEqualTo(TilePos.hires(-16, -16));
        assertThat(tiles.getLast()).isEqualTo(TilePos.hires(-1, -1));
    }
}
