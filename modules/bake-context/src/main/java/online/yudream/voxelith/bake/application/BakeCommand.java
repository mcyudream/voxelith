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
 */
public record BakeCommand(List<ChunkPos> chunks, Path outputDir, int sampleChunks) {
}
