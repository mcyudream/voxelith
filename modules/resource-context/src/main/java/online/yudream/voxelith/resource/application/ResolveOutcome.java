package online.yudream.voxelith.resource.application;

/**
 * resolve 链路输出摘要。
 */
public record ResolveOutcome(
        int blocksResolved,
        int blocksFound,
        int modelsResolved,
        int modelsFound,
        int texturesExported,
        double blockCoverage,
        double modelCoverage) {
}
