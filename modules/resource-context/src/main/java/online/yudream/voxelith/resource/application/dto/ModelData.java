package online.yudream.voxelith.resource.application.dto;

import java.util.List;
import java.util.Map;

/**
 * 展开后模型的跨上下文传输形态。
 *
 * @param textures         贴图变量 → 最终贴图 id（"minecraft:block/stone" 形式）
 * @param elements         模型元素
 * @param ambientOcclusion 是否启用 AO
 */
public record ModelData(Map<String, String> textures,
                        List<ElementData> elements,
                        boolean ambientOcclusion) {

    public record ElementData(float[] from, float[] to, RotationData rotation,
                              boolean shade, Map<String, FaceData> faces) {
    }

    public record RotationData(float[] origin, String axis, float angle, boolean rescale) {
    }

    /**
     * @param uv        [u1,v1,u2,v2]，null = 按 from/to 自动推导
     * @param texture   "#var" 引用
     * @param cullface  剔除方向名（"north" 等），null = 不剔除
     * @param rotation  uv 旋转
     * @param tintIndex 染色索引，-1 = 不染色
     */
    public record FaceData(float[] uv, String texture, String cullface, int rotation, int tintIndex) {
    }
}
