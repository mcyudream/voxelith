package online.yudream.voxelith.world.infrastructure.bootstrap;

import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;

import java.nio.file.Path;
import java.util.Map;

/**
 * 世界上下文组合根：按存档目录 + 维度装配方块访问契约。
 */
public final class WorldContextBootstrap {

    /** 维度 id → 相对存档根的目录（与 scan 链路一致）。 */
    private static final Map<String, String> DIMENSION_DIRS = Map.of(
            "minecraft:overworld", "",
            "minecraft:the_nether", "DIM-1",
            "minecraft:the_end", "DIM1");

    private WorldContextBootstrap() {
    }

    public static WorldBlockAccess openBlockAccess(Path worldDir, String dimension) {
        String relative = DIMENSION_DIRS.get(dimension);
        if (relative == null) {
            throw new IllegalArgumentException("未知维度: " + dimension);
        }
        Path dimensionDir = relative.isEmpty() ? worldDir : worldDir.resolve(relative);
        return new AnvilWorldBlockAccess(new AnvilWorldReader(), dimensionDir);
    }
}
