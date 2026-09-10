package online.yudream.voxelith.runtime.application;

import online.yudream.voxelith.runtime.domain.HarvestReport;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import online.yudream.voxelith.runtime.domain.RuntimeWorkerLauncher;

import java.nio.file.Files;

/**
 * 模型采集用例：校验运行规格后委派 worker 启动器执行 headless 采集。
 * 一期仅打通"子进程自检"链路（LWJGL stub 可行性），真实 BakedModel 采集后续接入。
 */
public final class HarvestModelsUseCase {

    private final RuntimeWorkerLauncher launcher;

    public HarvestModelsUseCase(RuntimeWorkerLauncher launcher) {
        this.launcher = launcher;
    }

    public HarvestReport harvest(RuntimeSpec spec) {
        for (var modJar : spec.modJars()) {
            if (!Files.isRegularFile(modJar)) {
                throw new IllegalArgumentException("mod jar 不存在: " + modJar);
            }
        }
        return launcher.launch(spec);
    }
}
