package online.yudream.voxelith.runtime.application;

import online.yudream.voxelith.resource.application.ResolveOutcome;
import online.yudream.voxelith.runtime.domain.HarvestReport;
import online.yudream.voxelith.runtime.domain.LoaderKind;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import online.yudream.voxelith.runtime.infrastructure.fallback.JsonModelAcquisitionSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级兜底语义：runtime 采集成功不触发静态解析；失败则回退并在
 * model-acquisition.json 中标记来源与失败明细。
 */
class HarvestWithFallbackUseCaseTest {

    @TempDir
    Path workDir;

    @Test
    void runtimeSuccessSkipsStaticFallback() throws Exception {
        HarvestReport ok = new HarvestReport(true, Map.of(), 1003, List.of(), 1000,
                24_135, 295_546, "models.json.gz");
        // 产物必须真实存在：只认报告不认文件会让管线拿着空路径继续跑
        Files.writeString(workDir.resolve("models.json.gz"), "{}");
        AtomicBoolean staticCalled = new AtomicBoolean(false);
        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                spec -> ok,
                (packs, outputDir) -> {
                    staticCalled.set(true);
                    throw new AssertionError("runtime 成功时不应触发静态兜底");
                },
                new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir);
        ModelAcquisition acquisition = useCase.acquire(spec, workDir.resolve("client.jar"));

        assertThat(staticCalled).isFalse();
        assertThat(acquisition.source()).isEqualTo(ModelSource.RUNTIME_HARVEST);
        assertThat(acquisition.ok()).isTrue();
        assertThat(acquisition.statesExported()).isEqualTo(24_135);
        assertThat(acquisition.quadsExported()).isEqualTo(295_546);
        assertThat(acquisition.runtimeFailures()).isEmpty();
        assertThat(acquisition.artifactPath()).isEqualTo(workDir.resolve("models.json.gz"));
    }

    @Test
    void okReportWithoutModelArtifactFallsBackToStaticResolve() {
        // 纯 stub 自检模式：worker 自检全过、ok=true，但没有 modelsFile，
        // 旧实现在这里 spec.workDir().resolve(null) 直接 NPE，或者谎报成功。
        HarvestReport okWithoutModels = new HarvestReport(true, Map.of("spec.parsed", true),
                0, List.of(), 120, 0, 0, null);
        AtomicBoolean staticCalled = new AtomicBoolean(false);
        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                spec -> okWithoutModels,
                (packs, outputDir) -> {
                    staticCalled.set(true);
                    return new ResolveOutcome(880, 1003, 3600, 3600, 1200, 0.88, 1.0);
                },
                new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir);
        ModelAcquisition acquisition = useCase.acquire(spec, workDir.resolve("client.jar"));

        assertThat(staticCalled).isTrue();
        assertThat(acquisition.source()).isEqualTo(ModelSource.STATIC_FALLBACK);
        assertThat(acquisition.blocksResolved()).isEqualTo(880);
        assertThat(acquisition.runtimeFailures())
                .anyMatch(f -> f.contains("未进入 fabric 采集阶段"));
    }

    @Test
    void okReportWithMissingModelFileFallsBackToStaticResolve() throws Exception {
        HarvestReport okButFileGone = new HarvestReport(true, Map.of(), 1003, List.of(), 1000,
                24_135, 295_546, "models.json.gz");
        // 报告里的文件名存在，但磁盘上没有（导出静默失败/被清理）
        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                spec -> okButFileGone,
                (packs, outputDir) -> new ResolveOutcome(870, 1003, 3500, 3500, 0, 0.87, 1.0),
                new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir);
        ModelAcquisition acquisition = useCase.acquire(spec, workDir.resolve("client.jar"));

        assertThat(acquisition.source()).isEqualTo(ModelSource.STATIC_FALLBACK);
        assertThat(acquisition.runtimeFailures())
                .anyMatch(f -> f.contains("模型文件不存在"));
    }

    @Test
    void runtimeFailureFallsBackToStaticResolveAndMarksReport() throws Exception {
        HarvestReport failed = HarvestReport.failed(List.of("worker 超时，已强制终止"), 5000);
        Path assetJar = Files.writeString(workDir.resolve("client.jar"), "fake");
        AtomicBoolean staticCalled = new AtomicBoolean(false);
        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                spec -> failed,
                (packs, outputDir) -> {
                    staticCalled.set(true);
                    assertThat(packs).containsExactly(assetJar);
                    return new ResolveOutcome(900, 1003, 3691, 3691, 1500, 0.9, 1.0);
                },
                new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir);
        ModelAcquisition acquisition = useCase.acquire(spec, assetJar);

        assertThat(staticCalled).isTrue();
        assertThat(acquisition.source()).isEqualTo(ModelSource.STATIC_FALLBACK);
        assertThat(acquisition.ok()).isTrue();
        assertThat(acquisition.blocksResolved()).isEqualTo(900);
        assertThat(acquisition.blocksFound()).isEqualTo(1003);
        assertThat(acquisition.runtimeFailures()).containsExactly("worker 超时，已强制终止");
        assertThat(acquisition.artifactPath())
                .isEqualTo(workDir.resolve(HarvestWithFallbackUseCase.STATIC_RESOLVE_DIR));

        // 报告落盘标记：来源 = static-fallback，附 runtime 失败明细
        String marker = Files.readString(
                workDir.resolve(JsonModelAcquisitionSink.FILE_NAME));
        assertThat(marker).contains("\"source\":\"STATIC_FALLBACK\"")
                .contains("\"ok\":true")
                .contains("worker 超时，已强制终止")
                .contains("\"blocksResolved\":900");
    }

    @Test
    void runtimeFailureWithEmptyStaticResolveIsNotOk() {
        HarvestReport failed = HarvestReport.failed(List.of("boom"), 10);
        HarvestWithFallbackUseCase useCase = new HarvestWithFallbackUseCase(
                spec -> failed,
                (packs, outputDir) -> new ResolveOutcome(0, 1003, 0, 0, 0, 0.0, 0.0),
                new JsonModelAcquisitionSink());

        RuntimeSpec spec = new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir);
        ModelAcquisition acquisition = useCase.acquire(spec, workDir.resolve("client.jar"));

        assertThat(acquisition.source()).isEqualTo(ModelSource.STATIC_FALLBACK);
        assertThat(acquisition.ok()).isFalse();
    }
}
