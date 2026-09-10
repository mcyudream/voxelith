package online.yudream.voxelith.world.domain.world;

/**
 * 世界存档元信息（来自 level.dat）。
 */
public record LevelInfo(String versionName, int dataVersion) {
}
