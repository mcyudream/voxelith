package online.yudream.voxelith.tile.domain.tile;

import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.application.dto.BakedQuadData;
import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.tile.TileGeometry.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把一个瓦片内的烘焙四边形列表组装为瓦片局部坐标几何：
 * 顶点坐标减去瓦片原点 (tileX*32, 0, tileZ*32)，uv 经图集布局重映射，
 * 染色与逐顶点光照/AO 打包为 ubyte（ubyte normalized 编码在 glb 编码器完成）。
 * 半透明面片（水等）拆分为独立分段，供 glb 输出 BLEND primitive。
 */
public class TileMeshAssembler {

    public static final int HIRES_TILE_SIZE = 32;

    public TileGeometry assemble(int tileX, int tileZ, List<BakedQuadData> quads, AtlasLayout layout) {
        List<BakedQuadData> opaque = new ArrayList<>();
        List<BakedQuadData> translucent = new ArrayList<>();
        for (BakedQuadData quad : quads) {
            (quad.translucent() ? translucent : opaque).add(quad);
        }
        Segment opaqueSegment = assembleSegment(tileX, tileZ, opaque, layout);
        Segment translucentSegment = translucent.isEmpty()
                ? Segment.empty()
                : assembleSegment(tileX, tileZ, translucent, layout);
        float[][] bounds = worldBounds(quads);
        return new TileGeometry(opaqueSegment, translucentSegment, bounds[0], bounds[1]);
    }

    private Segment assembleSegment(int tileX, int tileZ, List<BakedQuadData> quads, AtlasLayout layout) {
        float originX = tileX * HIRES_TILE_SIZE;
        float originZ = tileZ * HIRES_TILE_SIZE;

        float[] positions = new float[quads.size() * 12];
        float[] normals = new float[quads.size() * 12];
        float[] uvs = new float[quads.size() * 8];
        byte[] colors = new byte[quads.size() * 12];
        byte[] lights = new byte[quads.size() * 12];
        int[] indices = new int[quads.size() * 6];

        for (int q = 0; q < quads.size(); q++) {
            BakedQuadData quad = quads.get(q);
            int vp = q * 12;
            int vu = q * 8;
            for (int v = 0; v < 4; v++) {
                positions[vp + v * 3] = quad.positions()[v * 3] - originX;
                positions[vp + v * 3 + 1] = quad.positions()[v * 3 + 1];
                positions[vp + v * 3 + 2] = quad.positions()[v * 3 + 2] - originZ;
                normals[vp + v * 3] = quad.normal()[0];
                normals[vp + v * 3 + 1] = quad.normal()[1];
                normals[vp + v * 3 + 2] = quad.normal()[2];
                float[] uv = layout.mapUv(quad.texture(), quad.uvs()[v * 2], quad.uvs()[v * 2 + 1]);
                uvs[vu + v * 2] = uv[0];
                uvs[vu + v * 2 + 1] = uv[1];
                // 染色是 sRGB 创作值；COLOR_0 顶点色约定为线性空间（three.js 顶点色不做色彩空间转换，
                // 与 GPU 解码到线性的纹素相乘才正确），故先转线性再量化
                int rgb = quad.tintRgb() >= 0 ? ColorSpace.srgbToLinearRgb(quad.tintRgb()) : -1;
                colors[vp + v * 3] = (byte) (rgb >= 0 ? (rgb >> 16) & 0xFF : 0xFF);
                colors[vp + v * 3 + 1] = (byte) (rgb >= 0 ? (rgb >> 8) & 0xFF : 0xFF);
                colors[vp + v * 3 + 2] = (byte) (rgb >= 0 ? rgb & 0xFF : 0xFF);
                lights[vp + v * 3] = (byte) (quad.skyLight()[v] * 17);
                lights[vp + v * 3 + 1] = (byte) (quad.blockLight()[v] * 17);
                lights[vp + v * 3 + 2] = (byte) (quad.ao()[v] * 85);
            }
            int vi = q * 4;
            int ii = q * 6;
            indices[ii] = vi;
            indices[ii + 1] = vi + 1;
            indices[ii + 2] = vi + 2;
            indices[ii + 3] = vi;
            indices[ii + 4] = vi + 2;
            indices[ii + 5] = vi + 3;
        }
        return new Segment(positions, normals, uvs, colors, lights, indices);
    }

    private static float[][] worldBounds(List<BakedQuadData> quads) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedQuadData quad : quads) {
            for (int i = 0; i < quad.positions().length; i += 3) {
                minX = Math.min(minX, quad.positions()[i]);
                maxX = Math.max(maxX, quad.positions()[i]);
                minY = Math.min(minY, quad.positions()[i + 1]);
                maxY = Math.max(maxY, quad.positions()[i + 1]);
                minZ = Math.min(minZ, quad.positions()[i + 2]);
                maxZ = Math.max(maxZ, quad.positions()[i + 2]);
            }
        }
        if (quads.isEmpty()) {
            minX = minY = minZ = maxX = maxY = maxZ = 0;
        }
        return new float[][]{{minX, minY, minZ}, {maxX, maxY, maxZ}};
    }

    /** 区块坐标 → hires 瓦片坐标（2×2 区块 = 1 瓦片，算术右移对负坐标同样正确）。 */
    public static TilePos tileOf(int chunkX, int chunkZ) {
        return TilePos.hires(chunkX >> 1, chunkZ >> 1);
    }

    /** 将区块网格按瓦片聚合（按瓦片坐标排序，保证产物确定性）。 */
    public static Map<TilePos, List<BakedQuadData>> group(Map<ChunkPos, BakedChunkMeshData> meshes) {
        Map<TilePos, List<BakedQuadData>> grouped = new TreeMap<>(
                Comparator.comparing((TilePos t) -> t.x()).thenComparing(TilePos::z));
        for (Map.Entry<ChunkPos, BakedChunkMeshData> entry : meshes.entrySet()) {
            TilePos tile = tileOf(entry.getKey().x(), entry.getKey().z());
            grouped.computeIfAbsent(tile, t -> new ArrayList<>()).addAll(entry.getValue().quads());
        }
        return grouped;
    }
}
