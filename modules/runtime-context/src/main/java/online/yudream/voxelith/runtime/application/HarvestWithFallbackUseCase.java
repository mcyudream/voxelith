package online.yudream.voxelith.runtime.application;

import online.yudream.voxelith.resource.application.ResolveOutcome;
import online.yudream.voxelith.runtime.domain.HarvestReport;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import online.yudream.voxelith.runtime.domain.RuntimeWorkerLauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型获取用例（含降级兜底，ADR 0001 风险对策）：优先拉起 headless worker
 * 采集真实 BakedModel；采集失败时回退到 jar 资源静态解析，并把 runtime 失败
 * 明细与来源标记写入 model-acquisition.json。
 */
public final class HarvestWithFallbackUseCase {

    /** 静态兜底产物目录名（workDir 相对）。 */
    public static final String STATIC_RESOLVE_DIR = "static-resolve";

    private final HarvestModelsUseCase harvest;
    private final StaticModelResolvePort staticResolve;
    private final ModelAcquisitionSink sink;

    public HarvestWithFallbackUseCase(RuntimeWorkerLauncher launcher,
                                      StaticModelResolvePort staticResolve,
                                      ModelAcquisitionSink sink) {
        this.harvest = new HarvestModelsUseCase(launcher);
        this.staticResolve = staticResolve;
        this.sink = sink;
    }

    /**
     * @param spec     headless 运行规格（workDir 同时承载采集产物与报告）
     * @param assetJar 兜底用的资源 jar（通常为原版客户端 jar，内含 assets/**）
     */
    public ModelAcquisition acquire(RuntimeSpec spec, Path assetJar) {
        HarvestReport report = harvest.harvest(spec);

        ModelAcquisition acquisition;
        if (report.ok()) {
            acquisition = new ModelAcquisition(
                    ModelSource.RUNTIME_HARVEST, true,
                    report.statesExported(), report.quadsExported(),
                    0, 0, List.of(),
                    spec.workDir().resolve(report.modelsFile()));
        } else {
            List<Path> packs = new ArrayList<>();
            packs.add(assetJar);
            packs.addAll(spec.modJars());
            Path outputDir = spec.workDir().resolve(STATIC_RESOLVE_DIR);
            ResolveOutcome outcome = staticResolve.resolve(packs, outputDir);
            acquisition = new ModelAcquisition(
                    ModelSource.STATIC_FALLBACK, outcome.blocksResolved() > 0,
                    0, 0,
                    outcome.blocksResolved(), outcome.blocksFound(),
                    report.failures(), outputDir);
        }
        sink.write(spec.workDir(), acquisition);
        return acquisition;
    }
}
