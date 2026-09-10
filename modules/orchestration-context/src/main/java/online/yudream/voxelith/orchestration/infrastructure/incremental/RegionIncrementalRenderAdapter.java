package online.yudream.voxelith.orchestration.infrastructure.incremental;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.bake.application.BakeOutcome;
import online.yudream.voxelith.bake.application.BakedMeshMapper;
import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.lod.application.GenerateLodPyramidUseCase;
import online.yudream.voxelith.lod.application.HeightfieldStore;
import online.yudream.voxelith.lod.application.LodCommand;
import online.yudream.voxelith.lod.application.LodOutcome;
import online.yudream.voxelith.orchestration.domain.IncrementalJob;
import online.yudream.voxelith.orchestration.domain.IncrementalPatch;
import online.yudream.voxelith.orchestration.domain.IncrementalRenderPort;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.application.TileCommand;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.world.application.WorldBlockAccess;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量渲染组合根：按 region 丢弃世界缓存 → bake 该 region 全部 32×32 区块
 * → 用已发布图集编码 hires → 与全图高度场合并后重网格覆盖这些瓦片的 LOD。
 * 跨上下文只依赖各 application 用例（ArchUnit 合法）。
 *
 * <p>region 被删除时仍 bake 一遍：空区块产出空网格，对应瓦片不落盘，
 * 返回 url → 空串，清单条目删除。新生成的瓦片进入 {@link IncrementalPatch#inserts()}。
 */
public final class RegionIncrementalRenderAdapter implements IncrementalRenderPort {

    private final WorldBlockAccess world;
    private final BakeChunksUseCase bake;
    private final GenerateTilesUseCase tiles;
    private final GenerateLodPyramidUseCase lod;
    private final PublishedAtlas atlas;
    private final HeightfieldStore heightfield;
    /** 发布地图根（{publishDir}/{mapId}），瓦片直接覆盖已发布 glb。 */
    private final Path mapDir;

    public RegionIncrementalRenderAdapter(WorldBlockAccess world,
                                          BakeChunksUseCase bake,
                                          GenerateTilesUseCase tiles,
                                          GenerateLodPyramidUseCase lod,
                                          PublishedAtlas atlas,
                                          HeightfieldStore heightfield,
                                          Path mapDir) {
        this.world = world;
        this.bake = bake;
        this.tiles = tiles;
        this.lod = lod;
        this.atlas = atlas;
        this.heightfield = heightfield;
        this.mapDir = mapDir;
    }

    @Override
    public IncrementalPatch rerender(IncrementalJob job) {
        Map<String, String> sha1ByUrl = new LinkedHashMap<>();
        List<IncrementalPatch.NewTile> inserts = new ArrayList<>();
        AtlasReuse reuse = atlas.load(job.mapId()).orElse(null);
        Path outputDir = mapDir;

        for (RegionPos region : job.regions()) {
            world.invalidateRegion(region);
            List<ChunkPos> chunks = chunksOf(region);
            BakeOutcome baked = bake.bake(new BakeCommand(chunks, outputDir.resolve("bake"), 0));
            Map<ChunkPos, BakedChunkMeshData> meshes = BakedMeshMapper.toData(baked.meshes());

            for (TilePos expected : expectedHiresTiles(region)) {
                sha1ByUrl.put(urlOf(expected), "");
            }
            TileOutcome hires = tiles.generate(new TileCommand(meshes, outputDir, reuse));
            for (TileOutcome.TileSummary summary : hires.tiles()) {
                recordSummary(sha1ByUrl, inserts, summary);
            }

            LodOutcome pyramid = lod.generate(new LodCommand(
                    meshes, outputDir, 0, heightfield, List.of(region)));
            for (TileOutcome.TileSummary summary : pyramid.tiles()) {
                recordSummary(sha1ByUrl, inserts, summary);
            }
        }
        return new IncrementalPatch(sha1ByUrl, inserts);
    }

    private static void recordSummary(Map<String, String> sha1ByUrl,
                                      List<IncrementalPatch.NewTile> inserts,
                                      TileOutcome.TileSummary summary) {
        String url = urlOf(summary.pos());
        sha1ByUrl.put(url, summary.sha1());
        var pos = summary.pos();
        inserts.add(new IncrementalPatch.NewTile(
                url, pos.level(), pos.x(), pos.z(), summary.sha1(),
                summary.bytes(), summary.quads(), summary.min(), summary.max()));
    }

    static List<ChunkPos> chunksOf(RegionPos region) {
        List<ChunkPos> chunks = new ArrayList<>(32 * 32);
        int baseX = region.x() << 5;
        int baseZ = region.z() << 5;
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                chunks.add(new ChunkPos(baseX + lx, baseZ + lz));
            }
        }
        return chunks;
    }

    /** region 覆盖的 hires 瓦片（2×2 区块 = 1 瓦片 → 16×16 瓦片/region）。 */
    static List<TilePos> expectedHiresTiles(RegionPos region) {
        List<TilePos> tiles = new ArrayList<>(16 * 16);
        int baseX = region.x() << 4;
        int baseZ = region.z() << 4;
        for (int tx = 0; tx < 16; tx++) {
            for (int tz = 0; tz < 16; tz++) {
                tiles.add(TilePos.hires(baseX + tx, baseZ + tz));
            }
        }
        return tiles;
    }

    static String urlOf(TilePos pos) {
        return pos.isHires()
                ? "tiles/hires/" + pos.x() + "/" + pos.z() + ".glb"
                : "tiles/lod/" + pos.level() + "/" + pos.x() + "/" + pos.z() + ".glb";
    }
}
