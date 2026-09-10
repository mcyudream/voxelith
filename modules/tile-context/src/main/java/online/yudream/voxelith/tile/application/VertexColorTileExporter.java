package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
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
 * 无纹理纯色瓦片导出：把世界坐标纯色四边形组装为瓦片局部几何并编码为
 * 无贴图 glb（COLOR_0 顶点色承载全部颜色，_LIGHT 合成全亮天空光使昼夜调光一致生效）。
 * 供 lod 等下游上下文经 application 层复用 tile 侧的 glb 编码与落盘能力。
 */
public class VertexColorTileExporter {

    private final TileEncoder tileEncoder;
    private final TileArtifactSink sink;

    public VertexColorTileExporter(TileEncoder tileEncoder, TileArtifactSink sink) {
        this.tileEncoder = tileEncoder;
        this.sink = sink;
    }

    public TileOutcome.TileSummary export(Path outputDir, TilePos pos, List<VertexColorQuadData> quads) {
        float originX = pos.x() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);
        float originZ = pos.z() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);

        int quadCount = quads.size();
        float[] positions = new float[quadCount * 12];
        float[] normals = new float[quadCount * 12];
        byte[] colors = new byte[quadCount * 12];
        byte[] lights = new byte[quadCount * 12];
        int[] indices = new int[quadCount * 6];

        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int q = 0; q < quadCount; q++) {
            VertexColorQuadData quad = quads.get(q);
            int vp = q * 12;
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
                colors[vp + v * 3] = (byte) ((quad.rgb() >> 16) & 0xFF);
                colors[vp + v * 3 + 1] = (byte) ((quad.rgb() >> 8) & 0xFF);
                colors[vp + v * 3 + 2] = (byte) (quad.rgb() & 0xFF);
                // 全亮天空光：方向明暗已烘进颜色，天空光等级拉满使昼夜强度参数照常生效
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

        Segment opaque = new Segment(positions, normals, new float[0], colors, lights, indices);
        TileGeometry geometry = new TileGeometry(opaque, Segment.empty(), min, max);
        byte[] glb = tileEncoder.encode(geometry, null);
        sink.writeTile(outputDir, pos, glb);
        return new TileOutcome.TileSummary(
                pos, quadCount, opaque.vertexCount(), glb.length, sha1(glb), min, max);
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
