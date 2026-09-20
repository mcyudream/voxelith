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
import online.yudream.voxelith.tile.application.EnsureAtlasCapacityUseCase;
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
    /** 可选：增量扩图集（缺贴图时把新贴图追加进已发布图集）。null = 不扩容（新贴图落兜底格）。 */
    private final EnsureAtlasCapacityUseCase atlasExpander;
    private final HeightfieldStore heightfield;
    /**
     * 增量瓦片是否启用 meshopt 熵编码。
     *
     * <p>编码选项整体在 {@code TileCommand.incremental(...)} 里组装（application 层）——
     * 编排域不能直接引用 tile.domain 的 {@code EncodeOptions}（ArchUnit 守护）。</p>
     */
    private final boolean meshopt;
    /** 发布地图根（{publishDir}/{mapId}），瓦片直接覆盖已发布 glb。 */
    private final Path mapDir;

    public RegionIncrementalRenderAdapter(WorldBlockAccess world,
                                          BakeChunksUseCase bake,
                                          GenerateTilesUseCase tiles,
                                          GenerateLodPyramidUseCase lod,
                                          PublishedAtlas atlas,
                                          HeightfieldStore heightfield,
                                          Path mapDir) {
        this(world, bake, tiles, lod, atlas, null, heightfield, mapDir);
    }

    public RegionIncrementalRenderAdapter(WorldBlockAccess world,
                                          BakeChunksUseCase bake,
                                          GenerateTilesUseCase tiles,
                                          GenerateLodPyramidUseCase lod,
                                          PublishedAtlas atlas,
                                          EnsureAtlasCapacityUseCase atlasExpander,
                                          HeightfieldStore heightfield,
                                          Path mapDir) {
        this(world, bake, tiles, lod, atlas, atlasExpander, heightfield, mapDir,
                DEFAULT_MESHOPT);
    }

    /**
     * @param meshopt 增量瓦片是否启用 meshopt 熵编码（与全量渲染的 {@code render.meshopt} 同源）
     */
    public RegionIncrementalRenderAdapter(WorldBlockAccess world,
                                          BakeChunksUseCase bake,
                                          GenerateTilesUseCase tiles,
                                          GenerateLodPyramidUseCase lod,
                                          PublishedAtlas atlas,
                                          EnsureAtlasCapacityUseCase atlasExpander,
                                          HeightfieldStore heightfield,
                                          Path mapDir,
                                          boolean meshopt) {
        this.world = world;
        this.bake = bake;
        this.tiles = tiles;
        this.lod = lod;
        this.atlas = atlas;
        this.atlasExpander = atlasExpander;
        this.heightfield = heightfield;
        this.mapDir = mapDir;
        this.meshopt = meshopt;
    }

    /** 增量瓦片的默认压缩开关（与全量渲染一致）。 */
    public static final boolean DEFAULT_MESHOPT = true;

    /** 当前是否启用 meshopt（组合根与测试用来核对增量与全量是否一致）。 */
    public boolean meshopt() {
        return meshopt;
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

            // 增量扩图集：这次用到的贴图若不在已发布图集里（新方块/mod 方块），先追加进去。
            // 不补的话这些面会整片渲染成品红兜底格。老单元格序号不动，已发布瓦片 UV 依旧有效。
            if (reuse != null && atlasExpander != null) {
                reuse = atlasExpander.ensure(job.mapId(), reuse,
                        GenerateTilesUseCase.usedTextures(meshes)).atlas();
            }

            for (TilePos expected : expectedHiresTiles(region)) {
                sha1ByUrl.put(urlOf(expected), "");
            }
            // 编码选项在 tile 的 application 层组装：共享图集（不内嵌 PNG）+ 可选 meshopt，
            // 与全量渲染同款；此前默认 uncompressed 让增量瓦片大一个数量级
            TileOutcome hires = tiles.generate(
                    TileCommand.incremental(meshes, outputDir, reuse, meshopt));
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
