package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;

import java.nio.file.Path;
import java.util.List;

/**
 * @param tiles        生成的瓦片摘要（含 sha1 与包围盒，供 manifest 链路复用）
 * @param atlasFile    图集 PNG 落盘路径
 * @param reportFile   报告落盘路径
 * @param textureCount 入集贴图数（含兜底格）
 * @param atlasSize    图集边长（像素）
 * @param missingTextures 模型引用、但资源包里找不到的贴图 id（会用品红兜底格渲染）。
 *                        典型的成因是「资源包版本与世界版本不匹配」——比如 1.20.3 起
 *                        {@code grass} 改名为 {@code short_grass}，拿 1.20.1 的资源包解析
 *                        1.21 的世界，草地就会整片变成品红。留出来给调用方报给用户。
 * @param untexturedQuads 连贴图 id 都没有的面（模型本身没引用贴图）；同样落到第 0 格兜底
 */
public record TileOutcome(List<TileSummary> tiles, Path atlasFile, Path reportFile,
                          int textureCount, int atlasSize,
                          List<String> missingTextures, int untexturedQuads) {

    public TileOutcome {
        missingTextures = missingTextures == null ? List.of() : List.copyOf(missingTextures);
    }

    public record TileSummary(TilePos pos, int quads, int vertices, int bytes, String sha1,
                              float[] min, float[] max) {
    }
}
