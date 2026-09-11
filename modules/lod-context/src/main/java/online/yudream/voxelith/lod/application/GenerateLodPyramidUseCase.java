package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.lod.domain.heightfield.AerialRaster;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.lod.domain.heightfield.HeightfieldLodMesher;
import online.yudream.voxelith.lod.domain.heightfield.LodQuad;
import online.yudream.voxelith.lod.domain.heightfield.LodSample;
import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.application.VertexColorQuadData;
import online.yudream.voxelith.tile.application.VertexColorTileExporter;
import online.yudream.voxelith.tile.domain.atlas.ImageCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lod 链路用例：bake 内存网格 → 朝上表面采样（UV 区域均色 × 群系染色）→ 柱状高度场金字塔
 * （level 1 起逐层 2×2 聚合）+ 世界 XZ 航拍色图 → 每瓦片顶面网格 + 裙边，
 * 经 tile 侧导出（有 ImageCodec 时内嵌色图 + UV，否则纯顶点色）。
 *
 * 层级 L 瓦片 (x, z) 为 32×32 柱、每柱 2^L×2^L 方块，覆盖 hires 瓦片
 * [x·2^L, (x+1)·2^L) × [z·2^L, (z+1)·2^L)，与 hires 网格严格对齐。
 */
public class GenerateLodPyramidUseCase {

    /** 自动模式的层级上限（防异常输入导致无限聚合）。 */
    private static final int LEVEL_CAP = 12;

    private final TextureColorSampler colorSampler;
    private final VertexColorTileExporter tileExporter;
    private final ImageCodec imageCodec;

    public GenerateLodPyramidUseCase(TextureColorSampler colorSampler,
                                     VertexColorTileExporter tileExporter) {
        this(colorSampler, tileExporter, null);
    }

    public GenerateLodPyramidUseCase(TextureColorSampler colorSampler,
                                     VertexColorTileExporter tileExporter,
                                     ImageCodec imageCodec) {
        this.colorSampler = colorSampler;
        this.tileExporter = tileExporter;
        this.imageCodec = imageCodec;
    }

    public LodOutcome generate(LodCommand command) {
        SampledSurfaces sampled = collectSamples(command.meshes());
        List<LodSample> samples = sampled.samples();
        AerialRaster raster = sampled.raster();
        HeightfieldLodMesher mesher = new HeightfieldLodMesher();
        List<TileOutcome.TileSummary> summaries = new ArrayList<>();

        Heightfield field = Heightfield.fromSamples(samples, 2);
        HeightfieldStore store = command.store();
        if (store != null) {
            Heightfield previous = store.load().orElse(null);
            if (previous != null && !command.replaceRegions().isEmpty()) {
                for (RegionPos region : command.replaceRegions()) {
                    int[] bounds = Heightfield.regionColumnBounds(region.x(), region.z(), previous.footprint());
                    previous = previous.clearColumns(bounds[0], bounds[1], bounds[2], bounds[3]);
                }
                field = previous.merge(field);
            } else if (previous != null && samples.isEmpty()) {
                field = previous;
            }
            store.save(field);
        }
        int level = 1;
        while (field.width() > 0 && level <= LEVEL_CAP) {
            int minTx = field.minTileX(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int maxTx = field.maxTileX(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int minTz = field.minTileZ(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int maxTz = field.maxTileZ(HeightfieldLodMesher.COLUMNS_PER_TILE);
            for (int tx = minTx; tx <= maxTx; tx++) {
                for (int tz = minTz; tz <= maxTz; tz++) {
                    if (!overlapsReplaced(command.replaceRegions(), level, tx, tz)) {
                        continue;
                    }
                    List<LodQuad> quads = mesher.meshTile(field, tx, tz);
                    if (quads.isEmpty()) {
                        continue;
                    }
                    List<VertexColorQuadData> data = new ArrayList<>(quads.size());
                    for (LodQuad quad : quads) {
                        data.add(new VertexColorQuadData(
                                quad.positions(), quad.normal(), quad.rgb(), quad.uvs()));
                    }
                    TilePos pos = new TilePos(level, tx, tz);
                    summaries.add(tileExporter.export(
                            command.outputDir(), pos, data, colormapPng(raster, level, tx, tz)));
                }
            }
            boolean reachedTop = command.maxLevel() > 0
                    ? level >= command.maxLevel()
                    : (maxTx - minTx + 1) <= 2 && (maxTz - minTz + 1) <= 2;
            if (reachedTop) {
                break;
            }
            field = field.aggregate();
            level++;
        }
        return new LodOutcome(summaries, level);
    }

    /** 增量只重网格覆盖被替换 region 的瓦片（含 ±1 裙边邻居）；全量则全部生成。 */
    private static boolean overlapsReplaced(List<RegionPos> regions, int level, int tx, int tz) {
        if (regions.isEmpty()) {
            return true;
        }
        int coverage = 32 << level;
        int minX = (tx - 1) * coverage;
        int maxX = (tx + 2) * coverage - 1;
        int minZ = (tz - 1) * coverage;
        int maxZ = (tz + 2) * coverage - 1;
        for (RegionPos region : regions) {
            int rMinX = region.x() << 9;
            int rMaxX = rMinX + 511;
            int rMinZ = region.z() << 9;
            int rMaxZ = rMinZ + 511;
            if (minX <= rMaxX && maxX >= rMinX && minZ <= rMaxZ && maxZ >= rMinZ) {
                return true;
            }
        }
        return false;
    }

    private byte[] colormapPng(AerialRaster raster, int level, int tx, int tz) {
        if (imageCodec == null || raster == null || raster.isEmpty()) {
            return null;
        }
        int coverage = HeightfieldLodMesher.COLUMNS_PER_TILE << level;
        int worldX = tx * coverage;
        int worldZ = tz * coverage;
        int size = AerialRaster.TILE_TEXTURE_SIZE;
        int[] argb = raster.downsample(worldX, worldZ, coverage, size);
        return imageCodec.encodePng(size, size, argb);
    }

    /**
     * 朝上表面采样：质心 (x,z) + 顶点最高 y + UV 区域均色 × 群系染色。
     * 花/火把/草等 XZ 投影面积远小于半格的细面不进高度场，避免高空俯视出现红色噪点。
     * 同时把表面溅到 1 方块/格的航拍栅格，供 LOD 色图盒式下采样。
     */
    private SampledSurfaces collectSamples(Map<?, BakedChunkMeshData> meshes) {
        List<BakedQuadData> upFaces = new ArrayList<>();
        float worldMinX = Float.POSITIVE_INFINITY, worldMaxX = Float.NEGATIVE_INFINITY;
        float worldMinZ = Float.POSITIVE_INFINITY, worldMaxZ = Float.NEGATIVE_INFINITY;
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                if (quad.normal()[1] <= 0.5f) {
                    continue;
                }
                float[] p = quad.positions();
                for (int v = 0; v < 4; v++) {
                    float x = p[v * 3];
                    float z = p[v * 3 + 2];
                    worldMinX = Math.min(worldMinX, x);
                    worldMaxX = Math.max(worldMaxX, x);
                    worldMinZ = Math.min(worldMinZ, z);
                    worldMaxZ = Math.max(worldMaxZ, z);
                }
                upFaces.add(quad);
            }
        }
        AerialRaster raster = AerialRaster.empty();
        if (!upFaces.isEmpty()) {
            int originX = (int) Math.floor(worldMinX);
            int originZ = (int) Math.floor(worldMinZ);
            int width = (int) Math.ceil(worldMaxX) - originX;
            int depth = (int) Math.ceil(worldMaxZ) - originZ;
            raster = new AerialRaster(originX, originZ, Math.max(width, 1), Math.max(depth, 1));
        }
        List<LodSample> samples = new ArrayList<>();
        for (BakedQuadData quad : upFaces) {
            float[] p = quad.positions();
            float cx = 0, cz = 0, top = -Float.MAX_VALUE;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int v = 0; v < 4; v++) {
                float x = p[v * 3];
                float z = p[v * 3 + 2];
                cx += x;
                cz += z;
                top = Math.max(top, p[v * 3 + 1]);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            float area = Math.max(0f, (maxX - minX) * (maxZ - minZ));
            if (area < 0.5f) {
                continue;
            }
            int color = surfaceColor(quad);
            samples.add(new LodSample(cx / 4, top, cz / 4, color, area));
            raster.splat(minX, maxX, minZ, maxZ, top, color, area);
        }
        return new SampledSurfaces(samples, raster);
    }

    /**
     * 列顶颜色：贴图 UV 包围盒线性均值 × 群系染色（sRGB 先转线性）在线性空间相乘，
     * 产物即 glb COLOR_0 顶点色（线性字节，three.js 顶点色不做色彩空间转换）。
     */
    private int surfaceColor(BakedQuadData quad) {
        int base = colorSampler.sampleUvAverageRgb(quad.texture(), quad.uvs());
        if (base < 0) {
            base = 0xFFFFFF;
        }
        int tint = quad.tintRgb();
        if (tint < 0) {
            return base;
        }
        int linearTint = ColorSpace.srgbToLinearRgb(tint);
        int r = ((base >> 16) & 0xFF) * ((linearTint >> 16) & 0xFF) / 255;
        int g = ((base >> 8) & 0xFF) * ((linearTint >> 8) & 0xFF) / 255;
        int b = (base & 0xFF) * (linearTint & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }

    private record SampledSurfaces(List<LodSample> samples, AerialRaster raster) {
    }
}
