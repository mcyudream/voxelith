package online.yudream.voxelith.world.infrastructure.bootstrap;

import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.domain.world.LevelInfo;
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

    /**
     * 维度 id → 相对存档根的目录（{@code ""} / {@code DIM-1} / {@code DIM1}）。
     *
     * <p>存档布局的唯一真相：region、entities 都在维度目录下，而 {@code data/map_*.dat}
     * 等全局数据在存档根。此前这套映射在 5 处各写了一遍 switch，容易只改一半——
     * 地图画的展示框就漏掉了维度，导致下界/末地的地图画怎么都读不到。</p>
     */
    public static String dimensionSubPath(String dimension) {
        String relative = DIMENSION_DIRS.get(dimension);
        if (relative == null) {
            throw new IllegalArgumentException("未知维度: " + dimension);
        }
        return relative;
    }

    /** 维度目录：主世界 = 存档根，下界 = {@code DIM-1}，末地 = {@code DIM1}。 */
    public static Path dimensionDir(Path worldDir, String dimension) {
        String relative = dimensionSubPath(dimension);
        return relative.isEmpty() ? worldDir : worldDir.resolve(relative);
    }

    /**
     * 读 level.dat 的版本信息（版本名 + DataVersion），用于「资源包/采集版本与世界不一致」的告警。
     * 读不到时不抛错由调用方决定——渲染本身不需要版本号，不该因为告警逻辑把管线打断。
     */
    public static LevelInfo readLevelInfo(Path worldDir) {
        return new AnvilWorldReader().readLevelInfo(worldDir);
    }

    public static WorldBlockAccess openBlockAccess(Path worldDir, String dimension) {
        return new AnvilWorldBlockAccess(new AnvilWorldReader(), dimensionDir(worldDir, dimension));
    }
}
