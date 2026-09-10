package online.yudream.voxelith.world.application;

import java.nio.file.Path;

/**
 * scan 链路输入。
 *
 * @param worldDir  存档根目录（含 level.dat）
 * @param outputDir 产物落盘目录（scan-report.json / chunks.json）
 */
public record ScanCommand(Path worldDir, Path outputDir) {
}
