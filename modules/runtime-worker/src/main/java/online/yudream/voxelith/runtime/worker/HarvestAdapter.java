package online.yudream.voxelith.runtime.worker;

import java.nio.file.Path;
import java.util.List;

/**
 * 版本×加载器采集适配器（worker 进程内 SPI）。
 * 内置实现按游戏 ClassLoader 上的 intermediary 类/方法签名探测，
 * 不绑定单一 MC 版本；额外加载器（Forge 等）以独立 jar 提供
 * {@code META-INF/services/online.yudream.voxelith.runtime.worker.HarvestAdapter}。
 */
public interface HarvestAdapter {

    /** 适配器标识，写入 worker-result 便于审计。 */
    String id();

    /** 当前游戏 ClassLoader 是否具备本适配器所需的引导/烘焙 API。 */
    boolean supports(ClassLoader gameClassLoader);

    /**
     * 无头启动前序（DetectedVersion → SharedConstants.setVersion → Bootstrap），
     * 返回已注册的方块数。
     */
    int bootstrapRegistries(ClassLoader gameClassLoader) throws Exception;

    /**
     * 驱动 ModelLoader 全量烘焙并导出 {@code models.json.gz}。
     *
     * @param gameJar 原版 client jar（vanilla 资源包）
     * @param modJars 已由 Knot 加载的 mod jar，同时作为资源包叠进 ResourceManager
     */
    ModelHarvest.HarvestCounts harvest(ClassLoader gameClassLoader, Path gameJar,
                                       List<Path> modJars, Path outFile) throws Exception;
}
