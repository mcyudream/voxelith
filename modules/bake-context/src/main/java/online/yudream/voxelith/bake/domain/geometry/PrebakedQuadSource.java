package online.yudream.voxelith.bake.domain.geometry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 真实 BakedModel 预烘焙 quad 源（runtime-context models.json.gz 采集产物的消费端口）。
 * bake 链路按方块状态优先消费：返回的 quad 已含变体旋转（模型局部 0~16 空间），
 * 光照 / AO / 群系染色仍由 bake 链路逐顶点烘焙。
 * 返回 {@link Optional#empty()} 表示该方块无采集产物，调用方降级静态模型解析。
 */
public interface PrebakedQuadSource {

    Optional<List<Quad>> quads(String block, Map<String, String> properties);
}
