package online.yudream.voxelith.marker.application;

import online.yudream.voxelith.marker.domain.MarkerSet;

import java.util.List;

/**
 * 标注 JSON 编解码端口（协议格式见 {@code @yudream/voxelith-core} 的 markerSetSchema）。
 *
 * <p>放在 application 而不是 domain：domain 不许依赖 Gson 之类的框架；
 * 但 interfaces 层又必须能拿到「协议序列化」这个能力（不能直连 infrastructure），
 * 所以由 application 定接口、infrastructure 给实现。</p>
 */
public interface MarkerCodecPort {

    String write(String mapId, List<MarkerSet> sets);

    List<MarkerSet> read(String json);
}
