package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多遍渲染的切批与 LOD 层级：这两处算错就是「跨批 UV 错位 / 接缝露空」，
 * 而且只有真的跑一遍几万区块的图才看得出来，所以在这里用纯计算锁住。
 */
class RenderBatchPlanTest {

    /** 沿 x 方向铺 regions 个 region，每个 region 取 z=0..31 的 32 个区块。 */
    private static List<ChunkPos> regionStrip(int regions) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int rx = 0; rx < regions; rx++) {
            for (int cz = 0; cz < 32; cz++) {
                chunks.add(new ChunkPos(rx * 32, cz));
            }
        }
        return chunks;
    }

    @Test
    void splitsIntoBatchesWithoutLosingOrDuplicatingChunks() {
        List<ChunkPos> chunks = regionStrip(5);

        List<RenderMapCli.Batch> batches = RenderMapCli.planBatches(chunks, 64);

        // 每批凑够 64 就收口 → 5 个 region（每个 32 区块）切成 3 批（2+2+1 个 region）
        assertThat(batches).hasSize(3);
        Set<ChunkPos> seen = new HashSet<>();
        int total = 0;
        for (RenderMapCli.Batch batch : batches) {
            assertThat(batch.chunks().size()).isLessThanOrEqualTo(64);
            assertThat(batch.window()).hasSameSizeAs(batch.regions());
            total += batch.chunks().size();
            seen.addAll(batch.chunks());
        }
        assertThat(total).isEqualTo(chunks.size());
        assertThat(seen).hasSize(chunks.size());
    }

    @Test
    void singleBatchWhenWindowFitsTheBudget() {
        List<RenderMapCli.Batch> batches = RenderMapCli.planBatches(regionStrip(2), 4096);

        assertThat(batches).hasSize(1);
        assertThat(batches.getFirst().regions()).hasSize(2);
    }

    @Test
    void lodLevelCoversTheWholeWindow() {
        // 每个 hires 瓦片 2×2 区块：10×10 区块 → 5×5 瓦片 → 聚两次到 ≤2×2
        List<ChunkPos> square = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                square.add(new ChunkPos(x, z));
            }
        }
        assertThat(RenderMapCli.lodLevelForWindow(square)).isEqualTo(3);

        // 小窗口至少一层，且层级随范围单调不减
        assertThat(RenderMapCli.lodLevelForWindow(List.of(new ChunkPos(0, 0)))).isEqualTo(1);
        assertThat(RenderMapCli.lodLevelForWindow(regionStrip(40)))
                .isGreaterThan(RenderMapCli.lodLevelForWindow(square));
    }
}
