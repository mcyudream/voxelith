package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.nio.file.Path;
import java.util.List;

/**
 * bake 链路命令。
 *
 * @param chunks       待烘焙区块（来自 scan 链路区块清单）
 * @param outputDir    产物目录（work/bake）
 * @param sampleChunks 落盘几何样本的区块数量上限（其余区块只计数，防止大存档产物爆炸）
 * @param minY         最低渲染高度（含），{@link #NO_MIN_Y} = 不限制。低于该高度的方块不参与网格化：
 *                     地下洞穴/矿层对地表地图没有价值，却占了绝大部分几何与内存
 *                     （实测某 32×32 瓦片 10 万顶点里绝大多数在地下）。
 */
public record BakeCommand(List<ChunkPos> chunks, Path outputDir, int sampleChunks, int minY) {

    /** minY = 不限制最低高度。 */
    public static final int NO_MIN_Y = Integer.MIN_VALUE;

    public BakeCommand {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public BakeCommand(List<ChunkPos> chunks, Path outputDir, int sampleChunks) {
        this(chunks, outputDir, sampleChunks, NO_MIN_Y);
    }
}
