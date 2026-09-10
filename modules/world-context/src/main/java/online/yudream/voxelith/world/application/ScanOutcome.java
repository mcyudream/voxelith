package online.yudream.voxelith.world.application;

/**
 * scan 链路输出摘要。
 */
public record ScanOutcome(
        String versionName,
        int dataVersion,
        int dimensionCount,
        int regionCount,
        int chunkCount,
        int minChunkX, int minChunkZ,
        int maxChunkX, int maxChunkZ) {
}
