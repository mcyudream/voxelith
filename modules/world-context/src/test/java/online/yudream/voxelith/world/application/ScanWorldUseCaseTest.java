package online.yudream.voxelith.world.application;

import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import online.yudream.voxelith.world.infrastructure.artifact.FileScanArtifactSink;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScanWorldUseCaseTest {

    @TempDir
    Path worldDir;
    @TempDir
    Path outputDir;

    @Test
    void scansSyntheticWorldAndWritesArtifacts() throws Exception {
        // 跨 region 边界：x 从 -16 到 47 → region (-1,-1) 到 (0,0)
        new SyntheticWorldBuilder().flatGround(-16, -16, 48, 48, 60).write(worldDir);

        ScanWorldUseCase useCase = new ScanWorldUseCase(new AnvilWorldReader(), new FileScanArtifactSink());
        ScanOutcome outcome = useCase.scan(new ScanCommand(worldDir, outputDir));

        assertThat(outcome.versionName()).isEqualTo("1.20.1");
        assertThat(outcome.dimensionCount()).isEqualTo(1);
        assertThat(outcome.regionCount()).isEqualTo(4);
        assertThat(outcome.chunkCount()).isEqualTo(16);
        assertThat(outcome.minChunkX()).isEqualTo(-1);
        assertThat(outcome.maxChunkX()).isEqualTo(2);

        assertThat(outputDir.resolve("scan-report.json")).isRegularFile();
        String chunksJson = Files.readString(outputDir.resolve("chunks.json"));
        assertThat(chunksJson).contains("minecraft:overworld").contains("0,0").contains("-1,-1");
    }
}
