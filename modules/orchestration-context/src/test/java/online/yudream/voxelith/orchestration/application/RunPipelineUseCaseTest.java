package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineRun;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.PipelineStageExecutor;
import online.yudream.voxelith.orchestration.domain.StageStatus;
import online.yudream.voxelith.orchestration.infrastructure.checkpoint.JsonPipelineCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunPipelineUseCaseTest {

    @TempDir
    Path runDir;

    /** 每个阶段写一个同名标记文件作为产物。 */
    private Map<PipelineStage, PipelineStageExecutor> artifactExecutors(Map<PipelineStage, AtomicInteger> calls) {
        Map<PipelineStage, PipelineStageExecutor> executors = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.ordered()) {
            calls.put(stage, new AtomicInteger());
            executors.put(stage, (s, dir) -> {
                calls.get(s).incrementAndGet();
                String artifact = s.name().toLowerCase() + ".done";
                Files.write(dir.resolve(artifact), new byte[0]);
                return List.of(artifact);
            });
        }
        return executors;
    }

    @Test
    void fullRunCompletesAllStagesInOrder() {
        Map<PipelineStage, AtomicInteger> calls = new EnumMap<>(PipelineStage.class);
        RunPipelineUseCase useCase = new RunPipelineUseCase(
                new JsonPipelineCheckpointStore(), artifactExecutors(calls));

        PipelineRun run = useCase.run(runDir, "run-1", "swust");

        assertThat(run.finished()).isTrue();
        assertThat(run.stages().values()).allMatch(s -> s.status() == StageStatus.DONE);
        calls.values().forEach(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(runDir.resolve("pipeline-checkpoint.json")).exists();
    }

    @Test
    void resumeSkipsDoneStagesWithIntactArtifacts() {
        Map<PipelineStage, AtomicInteger> calls = new EnumMap<>(PipelineStage.class);
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        RunPipelineUseCase useCase = new RunPipelineUseCase(store, artifactExecutors(calls));
        useCase.run(runDir, "run-1", "swust");

        PipelineRun resumed = useCase.run(runDir, "run-1", "swust");

        assertThat(resumed.finished()).isTrue();
        calls.values().forEach(c -> assertThat(c.get()).as("已完成阶段不得重跑").isEqualTo(1));
    }

    @Test
    void missingArtifactRerunsOnlyThatStage() throws IOException {
        Map<PipelineStage, AtomicInteger> calls = new EnumMap<>(PipelineStage.class);
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        RunPipelineUseCase useCase = new RunPipelineUseCase(store, artifactExecutors(calls));
        useCase.run(runDir, "run-1", "swust");

        Files.delete(runDir.resolve("bake.done"));
        PipelineRun resumed = useCase.run(runDir, "run-1", "swust");

        assertThat(resumed.finished()).isTrue();
        assertThat(calls.get(PipelineStage.BAKE).get()).isEqualTo(2);
        assertThat(calls.get(PipelineStage.RESOLVE).get()).isEqualTo(1);
        assertThat(calls.get(PipelineStage.MANIFEST).get()).isEqualTo(1);
    }

    @Test
    void failureMarksStageAndInterruptsThenResumesFromFailure() {
        Map<PipelineStage, AtomicInteger> calls = new EnumMap<>(PipelineStage.class);
        Map<PipelineStage, PipelineStageExecutor> executors = artifactExecutors(calls);
        AtomicInteger tileAttempts = new AtomicInteger();
        executors.put(PipelineStage.TILE, (s, dir) -> {
            if (tileAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("模拟 tile 崩溃");
            }
            String artifact = "tile.done";
            Files.write(dir.resolve(artifact), new byte[0]);
            return List.of(artifact);
        });
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        RunPipelineUseCase useCase = new RunPipelineUseCase(store, executors);

        PipelineRun failed = useCase.run(runDir, "run-1", "swust");

        assertThat(failed.finished()).isFalse();
        assertThat(failed.stage(PipelineStage.TILE).status()).isEqualTo(StageStatus.FAILED);
        assertThat(failed.stage(PipelineStage.TILE).error()).contains("模拟 tile 崩溃");
        assertThat(failed.stage(PipelineStage.LOD).status()).isEqualTo(StageStatus.PENDING);
        assertThat(calls.get(PipelineStage.LOD).get()).isZero();

        PipelineRun resumed = useCase.run(runDir, "run-1", "swust");

        assertThat(resumed.finished()).isTrue();
        // 失败阶段重跑，其前的已完成阶段不重跑
        assertThat(tileAttempts.get()).isEqualTo(2);
        assertThat(calls.get(PipelineStage.BAKE).get()).isEqualTo(1);
    }

    @Test
    void differentRunIdStartsFresh() {
        Map<PipelineStage, AtomicInteger> calls = new EnumMap<>(PipelineStage.class);
        JsonPipelineCheckpointStore store = new JsonPipelineCheckpointStore();
        RunPipelineUseCase useCase = new RunPipelineUseCase(store, artifactExecutors(calls));
        useCase.run(runDir, "run-1", "swust");

        PipelineRun second = useCase.run(runDir, "run-2", "swust");

        assertThat(second.runId()).isEqualTo("run-2");
        calls.values().forEach(c -> assertThat(c.get()).isEqualTo(2));
    }
}
