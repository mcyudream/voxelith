package online.yudream.voxelith.runtime.worker;

import java.nio.file.Path;
import java.util.List;

/**
 * 采集门面：解析 {@link HarvestAdapter} 后委托。
 * 导出格式见 {@code docs/protocol/models-json.md}（voxelith-models/1）。
 */
public final class ModelHarvest {

    public record HarvestCounts(int blocks, int states, int quads) {
    }

    public static HarvestCounts harvest(ClassLoader gameClassLoader, Path gameJar, Path outFile)
            throws Exception {
        return harvest(gameClassLoader, gameJar, List.of(), outFile);
    }

    public static HarvestCounts harvest(ClassLoader gameClassLoader, Path gameJar,
                                        List<Path> modJars, Path outFile) throws Exception {
        HarvestAdapter adapter = HarvestAdapters.resolve(gameClassLoader);
        return adapter.harvest(gameClassLoader, gameJar, modJars, outFile);
    }

    private ModelHarvest() {
    }
}
