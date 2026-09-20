package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.world.domain.world.MapArtFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地图画注入：
 * <ul>
 *   <li>有地图 → 用地图贴图贴面片；</li>
 *   <li>没有地图（{@code data/map_*.dat} 缺失 / 未识别）→ **仍然画出展示框本体**
 *       （内置框体贴图），避免「墙上一片空」这种更难判断的状态；</li>
 *   <li>面片落进正确的区块（floorDiv，含负坐标）。</li>
 * </ul>
 */
class MapArtInjectorTest {

    private static final int[] MAP_PIXELS = new int[128 * 128];

    static {
        java.util.Arrays.fill(MAP_PIXELS, 0xFF00FF00);
    }

    private static BakedQuadData existingQuad() {
        return new BakedQuadData(
                new float[]{0, 64, 0, 1, 64, 0, 1, 64, 1, 0, 64, 1},
                new float[]{0, 16, 16, 16, 16, 0, 0, 0},
                new float[]{0, 1, 0}, "minecraft:block/stone", -1, true, "up",
                -1, new byte[]{15, 15, 15, 15}, new byte[4], new byte[4], false);
    }

    @Test
    @DisplayName("有地图时贴地图贴图，并注入到展示框所在区块")
    void injectsMapTextureWhenRegistered() {
        MapArtInjector injector = new MapArtInjector(id -> Optional.empty());
        injector.register(7, MAP_PIXELS);
        assertThat(injector.registeredCount()).isEqualTo(1);

        Map<ChunkPos, BakedChunkMeshData> meshes = Map.of(
                new ChunkPos(0, 0), new BakedChunkMeshData(new ChunkPos(0, 0),
                        List.of(existingQuad()), Map.of(), 1));
        Map<ChunkPos, BakedChunkMeshData> out = injector.inject(
                List.of(new MapArtFrame(20, 70, -3, "north", 7)), meshes);

        // x=20 → chunk 1；z=-3 → chunk -1（floorDiv）
        BakedChunkMeshData injected = out.get(new ChunkPos(1, -1));
        assertThat(injected).isNotNull();
        assertThat(injected.quads()).hasSize(1);
        assertThat(injected.quads().getFirst().texture()).isEqualTo("voxelith:map/7");
        assertThat(injector.framesWithoutMap()).isZero();
        // 原有区块不受影响
        assertThat(out.get(new ChunkPos(0, 0)).quads()).hasSize(1);
    }

    @Test
    @DisplayName("地图数据缺失时仍然画出空展示框（内置框体贴图）")
    void injectsEmptyFrameWhenMapMissing() {
        MapArtInjector injector = new MapArtInjector(id -> Optional.empty());
        // 故意不 register(7)：模拟地图文件缺失或未被识别
        Map<ChunkPos, BakedChunkMeshData> out = injector.inject(
                List.of(new MapArtFrame(0, 65, 0, "south", 7)), Map.of());

        assertThat(out).hasSize(1);
        BakedChunkMeshData injected = out.get(new ChunkPos(0, 0));
        assertThat(injected.quads()).hasSize(1);
        assertThat(injected.quads().getFirst().texture())
                .isEqualTo(MapArtInjector.EMPTY_FRAME_TEXTURE);
        assertThat(injector.framesWithoutMap()).isEqualTo(1);
        assertThat(injector.registeredCount()).as("空框不算地图").isZero();
    }

    @Test
    @DisplayName("内置框体贴图具备外圈木色与透明内圈")
    void emptyFrameTextureLooksLikeFrame() {
        MapArtInjector injector = new MapArtInjector(id -> Optional.empty());
        var texture = injector.load(Identifier.parse(MapArtInjector.EMPTY_FRAME_TEXTURE)).orElseThrow();
        assertThat(texture.cellWidth()).isEqualTo(16);
        // 外圈不透明、内部透明
        assertThat(texture.argb()[0] >>> 24).isEqualTo(0xFF);
        assertThat(texture.argb()[8 * 16 + 8] >>> 24).isZero();
    }

    @Test
    @DisplayName("非地图贴图照旧委托给被包装的像素源")
    void delegatesOtherTextures() {
        MapArtInjector injector = new MapArtInjector(id -> Optional.empty());
        assertThat(injector.load(Identifier.parse("minecraft:block/stone"))).isEmpty();
    }
}
