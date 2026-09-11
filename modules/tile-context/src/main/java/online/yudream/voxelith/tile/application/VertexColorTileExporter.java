package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.domain.tile.TileEncoder;
import online.yudream.voxelith.tile.domain.tile.TileGeometry;
import online.yudream.voxelith.tile.domain.tile.TileGeometry.Segment;
import online.yudream.voxelith.tile.domain.tile.TileMeshAssembler;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * LOD 瓦片导出：把世界坐标四边形组装为瓦片局部几何并编码为 glb。
 * 无色图时 COLOR_0 承载全部颜色；有航拍色图时写入 TEXCOORD_0 与内嵌 PNG，
 * COLOR_0 只承载方向明暗。_LIGHT 合成全亮天空光使昼夜调光一致生效。
 */
public class VertexColorTileExporter {

    private final TileEncoder tileEncoder;
    private final TileArtifactSink sink;
    private final EncodeOptions encode;

    public VertexColorTileExporter(TileEncoder tileEncoder, TileArtifactSink sink) {
        this(tileEncoder, sink, EncodeOptions.uncompressed());
    }

    public VertexColorTileExporter(TileEncoder tileEncoder, TileArtifactSink sink, EncodeOptions encode) {
        this.tileEncoder = tileEncoder;
        this.sink = sink;
        this.encode = encode;
    }

    public TileOutcome.TileSummary export(Path outputDir, TilePos pos, List<VertexColorQuadData> quads) {
        return export(outputDir, pos, quads, null);
    }

    /**
     * @param colormapPng 航拍色图 PNG；非空时写入 TEXCOORD_0 与内嵌贴图，COLOR_0 只承载方向明暗
     */
    public TileOutcome.TileSummary export(Path outputDir, TilePos pos, List<VertexColorQuadData> quads,
                                          byte[] colormapPng) {
        float originX = pos.x() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);
        float originZ = pos.z() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);

        int quadCount = quads.size();
        float[] positions = new float[quadCount * 12];
        float[] normals = new float[quadCount * 12];
        float[] uvs = colormapPng != null ? new float[quadCount * 8] : new float[0];
        byte[] colors = new byte[quadCount * 12];
        byte[] lights = new byte[quadCount * 12];
        int[] indices = new int[quadCount * 6];

        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int q = 0; q < quadCount; q++) {
            VertexColorQuadData quad = quads.get(q);
            int vp = q * 12;
            int vu = q * 8;
            float[] quadUv = quad.uvs();
            for (int v = 0; v < 4; v++) {
                float wx = quad.positions()[v * 3];
                float wy = quad.positions()[v * 3 + 1];
                float wz = quad.positions()[v * 3 + 2];
                positions[vp + v * 3] = wx - originX;
                positions[vp + v * 3 + 1] = wy;
                positions[vp + v * 3 + 2] = wz - originZ;
                normals[vp + v * 3] = quad.normal()[0];
                normals[vp + v * 3 + 1] = quad.normal()[1];
                normals[vp + v * 3 + 2] = quad.normal()[2];
                if (colormapPng != null) {
                    if (quadUv != null && quadUv.length >= (v + 1) * 2) {
                        uvs[vu + v * 2] = quadUv[v * 2];
                        uvs[vu + v * 2 + 1] = quadUv[v * 2 + 1];
                    }
                    byte shade = shadeFromNormal(quad.normal());
                    colors[vp + v * 3] = shade;
                    colors[vp + v * 3 + 1] = shade;
                    colors[vp + v * 3 + 2] = shade;
                } else {
                    colors[vp + v * 3] = (byte) ((quad.rgb() >> 16) & 0xFF);
                    colors[vp + v * 3 + 1] = (byte) ((quad.rgb() >> 8) & 0xFF);
                    colors[vp + v * 3 + 2] = (byte) (quad.rgb() & 0xFF);
                }
                lights[vp + v * 3] = (byte) (15 * 17);
                lights[vp + v * 3 + 1] = 0;
                lights[vp + v * 3 + 2] = 0;
                min[0] = Math.min(min[0], wx);
                max[0] = Math.max(max[0], wx);
                min[1] = Math.min(min[1], wy);
                max[1] = Math.max(max[1], wy);
                min[2] = Math.min(min[2], wz);
                max[2] = Math.max(max[2], wz);
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

        Segment opaque = new Segment(positions, normals, uvs, colors, lights, indices);
        TileGeometry geometry = new TileGeometry(opaque, Segment.empty(), min, max);
        EncodeOptions options = colormapPng != null ? EncodeOptions.lodColormap() : encode;
        byte[] glb = tileEncoder.encode(geometry, colormapPng, options);
        sink.writeTile(outputDir, pos, glb);
        return new TileOutcome.TileSummary(
                pos, quadCount, opaque.vertexCount(), glb.length, sha1(glb), min, max);
    }

    /** 有色图时 COLOR_0 只承载方向明暗：顶面 1.0、东西 0.6、南北 0.8。 */
    private static byte shadeFromNormal(float[] normal) {
        if (normal[1] > 0.5f) {
            return (byte) 255;
        }
        if (Math.abs(normal[0]) > 0.5f) {
            return (byte) 153;
        }
        return (byte) 204;
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
