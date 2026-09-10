package online.yudream.voxelith.lod.application;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.lod.domain.heightfield.HeightfieldLodMesher;
import online.yudream.voxelith.lod.domain.heightfield.LodQuad;
import online.yudream.voxelith.lod.domain.heightfield.LodSample;
import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.application.VertexColorQuadData;
import online.yudream.voxelith.tile.application.VertexColorTileExporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lod 链路用例：bake 内存网格 → 朝上表面采样（贴图均色 × 群系染色）→ 柱状高度场金字塔
 * （level 1 起逐层 2×2 聚合）→ 每瓦片顶面网格 + 裙边 → 经 tile 侧纯色瓦片导出落盘。
 *
 * 层级 L 瓦片 (x, z) 为 32×32 柱、每柱 2^L×2^L 方块，覆盖 hires 瓦片
 * [x·2^L, (x+1)·2^L) × [z·2^L, (z+1)·2^L)，与 hires 网格严格对齐。
 */
public class GenerateLodPyramidUseCase {

    /** 自动模式的层级上限（防异常输入导致无限聚合）。 */
    private static final int LEVEL_CAP = 12;

    private final TextureColorSampler colorSampler;
    private final VertexColorTileExporter tileExporter;

    public GenerateLodPyramidUseCase(TextureColorSampler colorSampler,
                                     VertexColorTileExporter tileExporter) {
        this.colorSampler = colorSampler;
        this.tileExporter = tileExporter;
    }

    public LodOutcome generate(LodCommand command) {
        List<LodSample> samples = collectSamples(command.meshes());
        HeightfieldLodMesher mesher = new HeightfieldLodMesher();
        List<TileOutcome.TileSummary> summaries = new ArrayList<>();

        Heightfield field = Heightfield.fromSamples(samples, 2);
        int level = 1;
        while (field.width() > 0 && level <= LEVEL_CAP) {
            int minTx = field.minTileX(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int maxTx = field.maxTileX(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int minTz = field.minTileZ(HeightfieldLodMesher.COLUMNS_PER_TILE);
            int maxTz = field.maxTileZ(HeightfieldLodMesher.COLUMNS_PER_TILE);
            for (int tx = minTx; tx <= maxTx; tx++) {
                for (int tz = minTz; tz <= maxTz; tz++) {
                    List<LodQuad> quads = mesher.meshTile(field, tx, tz);
                    if (quads.isEmpty()) {
                        continue;
                    }
                    List<VertexColorQuadData> data = new ArrayList<>(quads.size());
                    for (LodQuad quad : quads) {
                        data.add(new VertexColorQuadData(quad.positions(), quad.normal(), quad.rgb()));
                    }
                    summaries.add(tileExporter.export(
                            command.outputDir(), new TilePos(level, tx, tz), data));
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

    /** 朝上表面采样：质心 (x,z) + 顶点最高 y + 贴图均色 × 群系染色。 */
    private List<LodSample> collectSamples(Map<?, BakedChunkMeshData> meshes) {
        List<LodSample> samples = new ArrayList<>();
        for (BakedChunkMeshData mesh : meshes.values()) {
            for (BakedQuadData quad : mesh.quads()) {
                if (quad.normal()[1] <= 0.5f) {
                    continue;
                }
                float cx = 0, cz = 0, top = -Float.MAX_VALUE;
                for (int v = 0; v < 4; v++) {
                    cx += quad.positions()[v * 3];
                    cz += quad.positions()[v * 3 + 2];
                    top = Math.max(top, quad.positions()[v * 3 + 1]);
                }
                samples.add(new LodSample(cx / 4, top, cz / 4, surfaceColor(quad)));
            }
        }
        return samples;
    }

    /**
     * 列顶颜色：贴图线性均值 × 群系染色（sRGB 先转线性）在线性空间相乘，
     * 产物即 glb COLOR_0 顶点色（线性字节，three.js 顶点色不做色彩空间转换）。
     */
    private int surfaceColor(BakedQuadData quad) {
        int base = colorSampler.averageColorRgb(quad.texture());
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
}
