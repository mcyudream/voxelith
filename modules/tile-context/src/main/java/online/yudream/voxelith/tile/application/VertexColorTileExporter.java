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
 * 无色图时 COLOR_0 承载全部颜色；有航拍色图时写入 TEXCOORD_0（+ 可选内嵌 PNG），
 * COLOR_0 只承载方向明暗。_LIGHT 合成全亮天空光使昼夜调光一致生效。
 *
 * <p>两种纹理形态：</p>
 * <ul>
 *   <li><b>内嵌色图</b>（{@code colormapPng != null}）：逐瓦片一张 PNG，UV 为瓦片局部 0..1。
 *       增量重跑与本批次之前的产物走这条。</li>
 *   <li><b>层级图集页</b>（{@code uvRect != null} 且不内嵌 PNG）：UV 烘焙成图集坐标，贴图由同层
 *       共享的 lod-atlas 页在运行时提供——前端每层只解码一张纹理，而不是每片瓦片一张。</li>
 * </ul>
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
        return export(outputDir, pos, quads, colormapPng, null);
    }

    /**
     * @param colormapPng 内嵌色图 PNG；null = 不内嵌（纹理由层级图集页提供）
     * @param uvRect      图集 UV 矩形 {u0,v0,u1,v1}；非空时把四边形的瓦片局部 UV 线性重映射进去
     */
    public TileOutcome.TileSummary export(Path outputDir, TilePos pos, List<VertexColorQuadData> quads,
                                          byte[] colormapPng, float[] uvRect) {
        if (uvRect != null && uvRect.length != 4) {
            throw new IllegalArgumentException("uvRect 必须是 {u0,v0,u1,v1}，收到长度 " + uvRect.length);
        }
        boolean textured = colormapPng != null || uvRect != null;
        float originX = pos.x() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);
        float originZ = pos.z() * pos.coverage(TileMeshAssembler.HIRES_TILE_SIZE);

        int quadCount = quads.size();
        float[] positions = new float[quadCount * 12];
        float[] normals = new float[quadCount * 12];
        float[] uvs = textured ? new float[quadCount * 8] : new float[0];
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
                if (textured) {
                    if (quadUv != null && quadUv.length >= (v + 1) * 2) {
                        float u = quadUv[v * 2];
                        float w = quadUv[v * 2 + 1];
                        if (uvRect != null) {
                            u = uvRect[0] + u * (uvRect[2] - uvRect[0]);
                            w = uvRect[1] + w * (uvRect[3] - uvRect[1]);
                        }
                        uvs[vu + v * 2] = u;
                        uvs[vu + v * 2 + 1] = w;
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
        // 有色图的 LOD 瓦片固定「线性过滤 + 内嵌色图」，但 meshopt 开关跟随调用方配置
        EncodeOptions options = textured
                ? EncodeOptions.lodColormap().withMeshopt(encode.meshopt())
                : encode;
        byte[] glb = tileEncoder.encode(geometry, colormapPng, options);
        sink.writeTile(outputDir, pos, glb);
        return new TileOutcome.TileSummary(
                pos, quadCount, opaque.vertexCount(), glb.length, sha1(glb), min, max);
    }

    /**
     * 写 LOD 层级图集页（转交 tile 侧落盘端口）。
     * 放在这里是为了让 lod-context 不必额外依赖 tile 的 infrastructure——本类已持有装配好的
     * {@link TileArtifactSink}，而两个组合根都已把同一个 sink 交给了本类。
     *
     * @return 相对地图根的图集页 URL（写入清单 lodAtlases）
     */
    public String writeLodAtlas(Path outputDir, int level, byte[] png) {
        return sink.writeLodAtlas(outputDir, level, png);
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
