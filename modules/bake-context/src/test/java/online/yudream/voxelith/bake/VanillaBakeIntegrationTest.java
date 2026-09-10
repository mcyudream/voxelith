package online.yudream.voxelith.bake;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.bake.application.BakeOutcome;
import online.yudream.voxelith.bake.domain.mesh.BakedQuad;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.infrastructure.bootstrap.ResourceContextBootstrap;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原版端到端：真实 1.20.1 客户端 jar 资源目录 + 合成存档 → 烘焙。
 * jar 不存在时跳过（CI 无缓存场景）。
 */
class VanillaBakeIntegrationTest {

    private static final Path VANILLA_JAR = locateVanillaJar();

    /** 测试工作目录是模块目录，向上找到仓库根的 .cache。 */
    private static Path locateVanillaJar() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(Path.of(".cache", "minecraft", "client-1.20.1.jar"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of(".cache", "minecraft", "client-1.20.1.jar");
    }

    @TempDir
    Path worldDir;

    @TempDir
    Path outputDir;

    @Test
    void bakesSyntheticWorldWithVanillaResources() {
        Assumptions.assumeTrue(Files.isRegularFile(VANILLA_JAR), "原版客户端 jar 未缓存");
        // grass_block 有 snowy 属性，调色板必须写全状态（与游戏语义一致）
        SyntheticWorldBuilder builder = new SyntheticWorldBuilder();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 16; z++) {
                builder.setBlock(x, 0, z, "minecraft:bedrock");
                for (int y = 1; y < 61; y++) {
                    builder.setBlock(x, y, z, "minecraft:stone");
                }
                builder.setBlock(x, 61, z, "minecraft:dirt")
                        .setBlock(x, 62, z, "minecraft:dirt")
                        .setBlock(x, 63, z, "minecraft:dirt")
                        .setBlock(x, 64, z, "minecraft:grass_block[snowy=false]");
            }
        }
        builder.write(worldDir);

        BakeOutcome outcome;
        try (ResolvedResourceCatalog catalog = ResourceContextBootstrap.openCatalog(List.of(VANILLA_JAR));
             WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            outcome = new BakeChunksUseCase(catalog, world, new FileBakeArtifactSink())
                    .bake(new BakeCommand(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), outputDir, 1));
        }

        assertThat(outcome.chunksBaked()).isEqualTo(2);
        assertThat(outcome.quadsBaked()).isGreaterThan(1000);
        assertThat(outcome.missing()).isEmpty();

        // 草方块顶面贴图与 tint 符合原版语义
        List<BakedQuad> grassTops = outcome.meshes().values().stream()
                .flatMap(m -> m.quads().stream())
                .filter(q -> "up".equals(q.face())
                        && "minecraft:block/grass_block_top".equals(q.texture()))
                .toList();
        assertThat(grassTops).hasSize(512);
        assertThat(grassTops).allMatch(q -> q.tintIndex() == 0);
    }
}
