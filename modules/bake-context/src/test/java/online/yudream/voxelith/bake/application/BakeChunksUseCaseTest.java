package online.yudream.voxelith.bake.application;

import online.yudream.voxelith.bake.domain.mesh.StubCatalog;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BakeChunksUseCaseTest {

    @TempDir
    Path worldDir;

    @TempDir
    Path outputDir;

    @Test
    void bakesChunksAndWritesArtifacts() throws Exception {
        new SyntheticWorldBuilder().flatGround(0, 0, 32, 16, 64).write(worldDir);

        BakeOutcome outcome;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            outcome = new BakeChunksUseCase(StubCatalog.superflat(), world, new FileBakeArtifactSink())
                    .bake(new BakeCommand(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), outputDir, 4));
        }

        assertThat(outcome.chunksBaked()).isEqualTo(2);
        assertThat(outcome.blocksBaked()).isEqualTo(2 * 16 * 16 * 65);
        assertThat(outcome.quadsBaked()).isPositive();
        assertThat(outcome.missing()).isEmpty();
        assertThat(outcome.meshes()).hasSize(2);

        assertThat(outcome.sampleFiles()).hasSize(2);
        for (Path sample : outcome.sampleFiles()) {
            assertThat(Files.isRegularFile(sample)).isTrue();
        }
        String report = Files.readString(outputDir.resolve("bake-report.json"));
        assertThat(report).contains("\"chunksBaked\": 2");
        assertThat(report).contains("\"quadsBaked\": " + outcome.quadsBaked());
    }
}
