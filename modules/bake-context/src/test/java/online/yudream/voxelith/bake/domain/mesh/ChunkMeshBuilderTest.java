package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.mesh.ChunkMeshBuilder.ChunkMeshResult;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import online.yudream.voxelith.world.testfixtures.SyntheticWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkMeshBuilderTest {

    @TempDir
    Path worldDir;

    @Test
    void buriedFacesAreCulledAndSurfaceKeepsTopFace() {
        // 单区块 superflat：bedrock(0) / stone(1..60) / dirt(61..63) / grass(64)
        new SyntheticWorldBuilder().flatGround(0, 0, 16, 16, 64).write(worldDir);

        ChunkMeshResult result;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            result = new ChunkMeshBuilder(StubCatalog.superflat(), world).buildChunk(new ChunkPos(0, 0));
        }

        assertThat(result.missing()).isEmpty();
        assertThat(result.blocksBaked()).isEqualTo(16 * 16 * 65);

        // 地表 256 个 grass_block 全部保留顶面
        List<BakedQuad> tops = result.quads().stream()
                .filter(q -> q.face().equals("up")
                        && "minecraft:block/grass_block_top".equals(q.texture()))
                .toList();
        assertThat(tops).hasSize(256);
        for (BakedQuad top : tops) {
            assertThat(top.tintIndex()).isEqualTo(0);
            for (int i = 1; i < 12; i += 3) {
                assertThat(top.positions()[i]).isEqualTo(65f);
            }
        }

        // 深埋 stone 的内部面全部被邻居剔除；残留的 stone 面只能出现在区块边界列
        List<BakedQuad> stoneQuads = result.quads().stream()
                .filter(q -> "minecraft:block/stone".equals(q.texture()))
                .toList();
        for (BakedQuad quad : stoneQuads) {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 12; i += 3) {
                minX = Math.min(minX, quad.positions()[i]);
                maxX = Math.max(maxX, quad.positions()[i]);
                minZ = Math.min(minZ, quad.positions()[i + 2]);
                maxZ = Math.max(maxZ, quad.positions()[i + 2]);
            }
            assertThat(minX == 0f || maxX == 16f || minZ == 0f || maxZ == 16f)
                    .as("stone 内部面必须被剔除").isTrue();
        }

        // 区块边界（x=0/15, z=0/15）的侧面未被剔除（邻居区块不存在）
        assertThat(result.quads()).anyMatch(q -> q.face().equals("west"));
    }

    @Test
    void neighborChunkCullsSharedBorderFaces() {
        // 两个相邻区块的 superflat
        new SyntheticWorldBuilder().flatGround(0, 0, 32, 16, 64).write(worldDir);

        ChunkMeshResult first;
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(worldDir, "minecraft:overworld")) {
            first = new ChunkMeshBuilder(StubCatalog.superflat(), world).buildChunk(new ChunkPos(0, 0));
        }

        // x=15 列 grass 的东侧边面被 chunk(1,0) 的邻居遮挡：不存在顶点 x=16 的 east 面
        assertThat(first.quads().stream()
                .filter(q -> q.face().equals("east"))
                .filter(q -> q.positions()[0] == 16f && q.positions()[1] >= 64f && q.positions()[1] <= 65f))
                .isEmpty();
        // x=0 列西侧边面仍在（无更西区块）
        assertThat(first.quads()).anyMatch(q -> q.face().equals("west"));
    }
}
