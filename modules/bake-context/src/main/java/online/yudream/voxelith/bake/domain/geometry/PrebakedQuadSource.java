package online.yudream.voxelith.bake.domain.geometry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 真实 BakedModel 预烘焙 quad 源（runtime-context models.json.gz 采集产物的消费端口）。
 * bake 链路按方块状态优先消费：返回的 quad 已含变体旋转（模型局部 0~16 空间），
 * 光照 / AO / 群系染色仍由 bake 链路逐顶点烘焙。
 * 返回 {@link Optional#empty()} 表示该方块无采集产物，调用方降级静态模型解析。
 */
public interface PrebakedQuadSource {

    Optional<List<Quad>> quads(String block, Map<String, String> properties);

    /**
     * 采集产物里出现过的全部贴图 id。
     *
     * <p>多遍渲染要先知道「整个窗口可能用到哪些贴图」才能把图集一次性定死——
     * 各批共用同一张图集，UV 才能跨批一致；否则后一批遇到新贴图只能落品红兜底格。
     * 采集产物已经把「方块状态 → 面片 + 贴图 id」全存下来了，所以这里只是把它列出来，
     * 不需要真的烘一遍几何（那是分钟级的开销）。</p>
     */
    default Set<String> textures() {
        return Set.of();
    }
}
