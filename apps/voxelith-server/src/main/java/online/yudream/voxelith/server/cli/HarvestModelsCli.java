package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.runtime.application.HarvestWithFallbackUseCase;
import online.yudream.voxelith.runtime.application.ModelAcquisition;
import online.yudream.voxelith.runtime.application.ModelSource;
import online.yudream.voxelith.runtime.domain.LoaderKind;
import online.yudream.voxelith.runtime.domain.RuntimeProvisioner;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import online.yudream.voxelith.runtime.infrastructure.fallback.JsonModelAcquisitionSink;
import online.yudream.voxelith.runtime.infrastructure.process.ProcessRuntimeWorkerLauncher;
import online.yudream.voxelith.runtime.infrastructure.provision.HttpRuntimeProvisioner;
import online.yudream.voxelith.server.config.StaticModelResolveFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

/**
 * headless 采集生产入口（Phase 5）：装配 runtime-context 的进程隔离 worker 启动器与
 * 降级兜底用例，产出 {@code models.json.gz}（含 mod 方块的 BakedModel）。
 *
 * <p>这是仓库内把「采集」这一段真正跑起来的入口——此前 {@code RuntimeSpec} /
 * {@code ProcessRuntimeWorkerLauncher} / {@code HarvestWithFallbackUseCase} 只有测试构造，
 * 服务端运行时不可达，因此 bake 永远拿不到 runtime 采集产物（退回静态解析，mod 方块缺几何）。</p>
 *
 * <p>退出码：0 = 拿到可用模型集（runtime 采集或静态兜底），1 = 两者都失败，2 = 参数错误。</p>
 */
public final class HarvestModelsCli {

    private static final Logger log = LoggerFactory.getLogger(HarvestModelsCli.class);

    public static void main(String[] args) {
        HarvestCliOptions options;
        try {
            options = HarvestCliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println();
            System.err.println(HarvestCliOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(HarvestCliOptions.USAGE);
            return;
        }
        System.exit(run(options));
    }

    /** 按解析后的选项执行采集，返回进程退出码。便于测试直接调用而不退出 JVM。 */
    public static int run(HarvestCliOptions options) {
        RuntimeProvisioner provisioner = options.skipProvision() ? null : new HttpRuntimeProvisioner();
        if (provisioner == null) {
            log.warn("skipProvision=true：worker 只跑 LWJGL 自检，不会产出 models.json.gz，"
                    + "届时会按规则退回静态解析");
        }
        ProcessRuntimeWorkerLauncher launcher = new ProcessRuntimeWorkerLauncher(
                options.workerClasspath(),
                provisioner,
                provisioner == null ? null : options.provisionCacheDir(),
                Duration.ofMinutes(options.timeoutMinutes()));

        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                launcher, StaticModelResolveFactory.create(), new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec(options.mcVersion(), LoaderKind.FABRIC,
                options.loaderVersion(), options.modJars(), options.workDir());

        log.info("开始采集: mc={} loader=fabric {} mods={} workDir={}",
                spec.mcVersion(), spec.loaderVersion(), spec.modJars().size(),
                spec.workDir().toAbsolutePath());

        ModelAcquisition acquisition = useCase.acquire(spec, options.assetJar());
        report(options, acquisition);
        return acquisition.ok() ? 0 : 1;
    }

    private static void report(HarvestCliOptions options, ModelAcquisition acquisition) {
        Path reportFile = options.workDir().resolve(JsonModelAcquisitionSink.FILE_NAME);
        System.out.println();
        System.out.println("模型获取结果");
        System.out.println("  来源:     " + acquisition.source()
                + (acquisition.source() == ModelSource.RUNTIME_HARVEST ? "（runtime 全量采集，含 mod 方块）"
                : "（静态解析兜底）"));
        System.out.println("  可用:     " + acquisition.ok());
        if (acquisition.source() == ModelSource.RUNTIME_HARVEST) {
            System.out.println("  状态/Quad: " + acquisition.statesExported() + " / " + acquisition.quadsExported());
        } else {
            System.out.println("  方块解析: " + acquisition.blocksResolved() + " / " + acquisition.blocksFound());
        }
        System.out.println("  产物:     " + acquisition.artifactPath());
        System.out.println("  报告:     " + reportFile);
        if (!acquisition.runtimeFailures().isEmpty()) {
            System.out.println("  失败明细:");
            acquisition.runtimeFailures().forEach(f -> System.out.println("    - " + f));
        }
        if (!acquisition.ok()) {
            System.out.println();
            System.out.println("未拿到可用模型集：检查 worker.log（" + options.workDir().resolve("worker.log")
                    + "）与上面的失败明细。");
        } else if (acquisition.source() == ModelSource.RUNTIME_HARVEST) {
            System.out.println();
            System.out.println("下一步：让 bake 消费该产物——把 yudream.voxelith.work-dir 指向 "
                    + options.workDir().toAbsolutePath()
                    + "，或用 incremental.models-file 直接指定该文件。");
        }
    }

    private HarvestModelsCli() {
    }
}
