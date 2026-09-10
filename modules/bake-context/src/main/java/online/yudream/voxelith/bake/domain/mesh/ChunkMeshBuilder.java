package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.geometry.ModelGeometryBaker;
import online.yudream.voxelith.bake.domain.geometry.ModelOcclusion;
import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.bake.domain.mesh.VertexLightSampler.SectionGrid;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.ModelVariantData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.application.dto.BlockStateData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块网格化领域服务：把区块内每个非空气方块烘焙成世界空间 quad，
 * 按 cullface 做邻居遮挡剔除（跨区块边界通过 WorldBlockAccess 随机访问），
 * 并逐顶点烘焙天空光/方块光与角点 AO（按截面预取 18³ 邻域网格控制随机访问开销）。
 */
public final class ChunkMeshBuilder {

    private final ResolvedResourceCatalog catalog;
    private final WorldBlockAccess world;
    private final ModelGeometryBaker baker = new ModelGeometryBaker();
    private final VertexLightSampler lightSampler;
    private final FluidMesher fluidMesher;
    private final TintResolver tintResolver;
    /** runtime 采集的真实 BakedModel quad 源，null = 纯静态模型解析。 */
    private final PrebakedQuadSource prebaked;

    /** 遮挡判定缓存：模型 id + 方块 id → 各方向是否遮挡。 */
    private final Map<String, Map<Direction, Boolean>> occlusionCache = new ConcurrentHashMap<>();

    /** 满不透明方块缓存（AO 遮挡判定用）：方块 id + 属性 → 是否六面全遮挡。 */
    private final Map<String, Boolean> opaqueCubeCache = new ConcurrentHashMap<>();

    public ChunkMeshBuilder(ResolvedResourceCatalog catalog, WorldBlockAccess world) {
        this(catalog, world, new BiomeTintResolver(catalog, world), null);
    }

    public ChunkMeshBuilder(ResolvedResourceCatalog catalog, WorldBlockAccess world, TintResolver tintResolver) {
        this(catalog, world, tintResolver, null);
    }

    public ChunkMeshBuilder(ResolvedResourceCatalog catalog, WorldBlockAccess world,
                            TintResolver tintResolver, PrebakedQuadSource prebaked) {
        this.catalog = catalog;
        this.world = world;
        this.tintResolver = tintResolver;
        this.prebaked = prebaked;
        this.lightSampler = new VertexLightSampler(world, this::isOpaqueCube);
        this.fluidMesher = new FluidMesher(world, this::occludes, lightSampler, tintResolver);
    }

    public ChunkMeshResult buildChunk(ChunkPos pos) {
        List<BakedQuad> quads = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        int blocksBaked = 0;

        int baseX = pos.x() * 16;
        int baseZ = pos.z() * 16;
        for (int sectionY : world.sectionYs(pos)) {
            int baseY = sectionY * 16;
            SectionGrid lightGrid = lightSampler.buildGrid(baseX, baseY, baseZ);
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = baseX + lx;
                        int y = baseY + ly;
                        int z = baseZ + lz;
                        Optional<BlockStateData> state = world.blockStateAt(x, y, z);
                        if (state.isEmpty() || state.get().isAir()) {
                            continue;
                        }
                        blocksBaked++;
                        bakeBlock(state.get(), x, y, z, lightGrid, quads, missing);
                    }
                }
            }
        }
        return new ChunkMeshResult(pos, quads, missing, blocksBaked);
    }

    private void bakeBlock(BlockStateData state, int x, int y, int z, SectionGrid lightGrid,
                           List<BakedQuad> quads, Map<String, Integer> missing) {
        // 流体模型无 elements，走几何合成而非静态模型链
        if (FluidMesher.isFluid(state.block())) {
            quads.addAll(fluidMesher.mesh(state, x, y, z, lightGrid));
            return;
        }
        // 含水方块（waterlogged=true / 海草海带）：除自身模型外合成方块内水体，
        // 与模型是否解析成功无关（即使模型缺失，水体也不应留下空气洞）
        if (FluidMesher.isWaterlogged(state)) {
            quads.addAll(fluidMesher.meshWaterlogged(state, x, y, z, lightGrid));
        }
        // runtime 采集的真实 BakedModel 优先：quad 已含变体旋转，
        // 仍复用本链路的 cullface 剔除与光照/AO/染色烘焙
        if (prebaked != null) {
            Optional<List<Quad>> harvested = prebaked.quads(state.block(), state.properties());
            if (harvested.isPresent()) {
                for (Quad quad : harvested.get()) {
                    if (isCulled(quad, x, y, z)) {
                        continue;
                    }
                    quads.add(toWorld(quad, state.block(), x, y, z, lightGrid));
                }
                return;
            }
        }
        Identifier blockId = Identifier.parse(state.block());
        List<VariantGroup> groups = catalog.selectVariantGroups(blockId, state.properties());
        if (groups.isEmpty()) {
            missing.merge(state.block(), 1, Integer::sum);
            return;
        }
        for (VariantGroup group : groups) {
            ModelVariantData variant = VariantPicker.pick(group.alternatives(), x, y, z);
            Optional<ModelData> model = catalog.model(Identifier.parse(variant.model()));
            if (model.isEmpty()) {
                missing.merge(variant.model(), 1, Integer::sum);
                continue;
            }
            for (Quad quad : baker.bake(model.get(), variant.x(), variant.y())) {
                if (isCulled(quad, x, y, z)) {
                    continue;
                }
                quads.add(toWorld(quad, state.block(), x, y, z, lightGrid));
            }
        }
    }

    /** cullface 剔除：邻居在剔除方向上完全遮挡本面时丢弃。 */
    private boolean isCulled(Quad quad, int x, int y, int z) {
        if (quad.cullface() == null) {
            return false;
        }
        Direction dir = Direction.byName(quad.cullface());
        Optional<BlockStateData> neighbor = world.blockStateAt(x + dir.nx(), y + dir.ny(), z + dir.nz());
        if (neighbor.isEmpty() || neighbor.get().isAir()) {
            return false;
        }
        return occludes(neighbor.get(), dir.opposite());
    }

    private boolean occludes(BlockStateData neighbor, Direction towardsUs) {
        String cacheKey = neighbor.block() + neighbor.properties() + "@" + towardsUs;
        Map<Direction, Boolean> byDir = occlusionCache.computeIfAbsent(cacheKey, key -> new EnumMap<>(Direction.class));
        return byDir.computeIfAbsent(towardsUs, dir -> {
            Identifier blockId = Identifier.parse(neighbor.block());
            List<VariantGroup> groups = catalog.selectVariantGroups(blockId, neighbor.properties());
            // 遮挡判定只需任一命中模型为满方块（满方块性与状态朝向无关）
            for (VariantGroup group : groups) {
                Optional<ModelData> model = catalog.model(Identifier.parse(group.alternatives().getFirst().model()));
                if (model.isPresent() && ModelOcclusion.occludes(neighbor.block(), model.get(), dir)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** 满不透明方块（六面全遮挡）：AO 遮挡格判定。 */
    private boolean isOpaqueCube(BlockStateData state) {
        return opaqueCubeCache.computeIfAbsent(state.block() + state.properties(), key -> {
            for (Direction dir : Direction.values()) {
                if (!occludes(state, dir)) {
                    return false;
                }
            }
            return true;
        });
    }

    private BakedQuad toWorld(Quad quad, String blockId, int x, int y, int z, SectionGrid lightGrid) {
        float[] positions = quad.positions().clone();
        for (int i = 0; i < positions.length; i += 3) {
            positions[i] = positions[i] / 16f + x;
            positions[i + 1] = positions[i + 1] / 16f + y;
            positions[i + 2] = positions[i + 2] / 16f + z;
        }
        byte[] sky = new byte[4];
        byte[] block = new byte[4];
        byte[] ao = new byte[4];
        lightSampler.sample(quad, x, y, z, lightGrid, sky, block, ao);
        int tintRgb = quad.tintIndex() >= 0 ? tintResolver.tint(blockId, quad.tintIndex(), x, y, z) : -1;
        return new BakedQuad(positions, quad.uvs(), quad.normal(),
                quad.texture(), quad.tintIndex(), quad.shade(), quad.face(),
                tintRgb, sky, block, ao, false);
    }

    /**
     * 单区块烘焙结果。
     *
     * @param missing 缺失映射（方块 id 或模型 id → 次数）
     */
    public record ChunkMeshResult(ChunkPos pos, List<BakedQuad> quads,
                                  Map<String, Integer> missing, int blocksBaked) {
    }
}
