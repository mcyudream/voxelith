package online.yudream.voxelith.resource.application;

import online.yudream.voxelith.resource.infrastructure.artifact.FileResolveArtifactSink;
import online.yudream.voxelith.resource.infrastructure.pack.ResourcePackAutoFactory;
import online.yudream.voxelith.resource.infrastructure.parse.GsonBlockstateParser;
import online.yudream.voxelith.resource.infrastructure.parse.GsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对真实 1.20.1 原版客户端 jar 的 resolve 链路集成测试。
 * 需要仓库 .cache/minecraft/client-1.20.1.jar 存在（不存在则跳过，不拖垮 CI）。
 */
class VanillaResolveIntegrationTest {

    private static final List<Path> JAR_CANDIDATES = List.of(
            Path.of("../../.cache/minecraft/client-1.20.1.jar"),
            Path.of(".cache/minecraft/client-1.20.1.jar"));

    @TempDir
    Path outputDir;

    @Test
    void resolvesVanillaClientJarWithHighCoverage() throws Exception {
        Path jar = JAR_CANDIDATES.stream()
                .map(Path::toAbsolutePath)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        assumeTrue(jar != null, "未找到 .cache/minecraft/client-1.20.1.jar，跳过真实资源集成测试");

        ResolveResourcesUseCase useCase = new ResolveResourcesUseCase(
                new ResourcePackAutoFactory(),
                new GsonBlockstateParser(),
                new GsonModelParser(),
                new FileResolveArtifactSink());

        ResolveOutcome outcome = useCase.resolve(new ResolveCommand(List.of(jar), outputDir));

        System.out.printf("resolve: blocks %d/%d (%.2f%%), models %d/%d (%.2f%%), textures %d%n",
                outcome.blocksResolved(), outcome.blocksFound(), outcome.blockCoverage() * 100,
                outcome.modelsResolved(), outcome.modelsFound(), outcome.modelCoverage() * 100,
                outcome.texturesExported());

        assertThat(outcome.blocksFound()).isGreaterThan(1000);
        assertThat(outcome.blockCoverage()).isGreaterThan(0.95);
        assertThat(outcome.modelCoverage()).isGreaterThan(0.95);
        assertThat(outcome.texturesExported()).isGreaterThan(500);

        assertThat(outputDir.resolve("resolved-registry.json")).isRegularFile();
        assertThat(outputDir.resolve("resolve-report.json")).isRegularFile();
        assertThat(Files.readString(outputDir.resolve("resolved-registry.json")))
                .contains("minecraft:stone")
                .contains("minecraft:block/stone");
    }
}
