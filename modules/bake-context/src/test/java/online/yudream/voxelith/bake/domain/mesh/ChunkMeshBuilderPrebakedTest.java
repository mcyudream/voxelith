package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * runtime 采集产物（真实 BakedModel quad）优先于静态模型解析进烘焙的接线测试。
 */
class ChunkMeshBuilderPrebakedTest {

    @TempDir
    Path worldDir;

    /** 标记 quad：bedrock 底面换成采集产物贴图，验证优先消费而非静态模型。 */
    private static Quad markerQuad() {
        return new Quad(
                new float[]{0, 0, 16, 16, 0, 16, 16, 0, 0, 0, 0, 0},
                new float[]{0, 0, 16, 0, 16, 16, 0, 16},
                new float[]{0, -1, 0},
                "mymod:block/harvested", "down", -1, true, "down");
    }

    @Test
    void prebakedQuadsWinAndOtherBlocksKeepStaticPath() {
        new SyntheticWorldBuilder().flatGround(0, 0, 16, 16, 64).write(worldDir);

        ChunkMeshResult result;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            result = new ChunkMeshBuilder(StubCatalog.superflat(), world,
                    new BiomeTintResolver(StubCatalog.superflat(), world),
                    (block, properties) -> "minecraft:bedrock".equals(block)
                            ? Optional.of(List.of(markerQuad())) : Optional.empty())
                    .buildChunk(new ChunkPos(0, 0));
        }

        assertThat(result.missing()).isEmpty();

        // 底层 256 个 bedrock 全部来自采集产物（y=0 下方无邻居，cullface=down 不剔除）
        List<BakedQuad> harvested = result.quads().stream()
                .filter(q -> "mymod:block/harvested".equals(q.texture()))
                .toList();
        assertThat(harvested).hasSize(256);
        for (BakedQuad q : harvested) {
            assertThat(q.face()).isEqualTo("down");
            assertThat(q.positions()[1]).isEqualTo(0f);
        }
        // 静态 bedrock 贴图不得再出现
        assertThat(result.quads())
                .noneMatch(q -> "minecraft:block/bedrock".equals(q.texture()));

        // 未覆盖的方块仍走静态解析：地表 256 个 grass 顶面照常
        assertThat(result.quads().stream()
                .filter(q -> q.face().equals("up")
                        && "minecraft:block/grass_block_top".equals(q.texture())))
                .hasSize(256);
    }

    @Test
    void harvestedBlockAbsentFromStaticCatalogIsNotMissing() {
        new SyntheticWorldBuilder().flatGround(0, 0, 16, 16, 64).write(worldDir);

        ChunkMeshResult result;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            // 静态目录里没有 mod 方块，只有采集源认识它 → 不应记入 missing
            result = new ChunkMeshBuilder(StubCatalog.superflat(), world,
                    new BiomeTintResolver(StubCatalog.superflat(), world),
                    (block, properties) -> "mymod:machine".equals(block)
                            ? Optional.of(List.of(markerQuad())) : Optional.empty())
                    .buildChunk(new ChunkPos(0, 0));
        }
        // superflat 世界没有 mymod:machine，仅验证不抛异常、静态方块照常烘焙
        assertThat(result.blocksBaked()).isEqualTo(16 * 16 * 65);
        assertThat(result.missing()).isEmpty();
    }

    @Test
    void emptyPrebakedSourceKeepsStaticBehavior() {
        new SyntheticWorldBuilder().flatGround(0, 0, 16, 16, 64).write(worldDir);

        ChunkMeshResult withSource;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            withSource = new ChunkMeshBuilder(StubCatalog.superflat(), world,
                    new BiomeTintResolver(StubCatalog.superflat(), world),
                    (block, properties) -> Optional.empty())
                    .buildChunk(new ChunkPos(0, 0));
        }
        assertThat(withSource.missing()).isEmpty();
        assertThat(withSource.quads().stream()
                .filter(q -> q.face().equals("up")
                        && "minecraft:block/grass_block_top".equals(q.texture())))
                .hasSize(256);
    }
}
