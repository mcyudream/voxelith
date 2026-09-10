package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.List;
import java.util.Map;

/**
 * 模型 JSON 的原始解析结果（继承链未展开）。
 *
 * @param parent            父模型，null 表示继承链顶端
 * @param textures          贴图变量表，值可能是 "#var" 引用或真实贴图 id
 * @param elements          元素列表；为空表示继承父模型元素
 * @param ambientOcclusion  环境光遮蔽开关，null 表示继承
 */
public record ModelDefinition(
        Identifier id,
        Identifier parent,
        Map<String, String> textures,
        List<ModelElement> elements,
        Boolean ambientOcclusion) {

    public boolean hasElements() {
        return elements != null && !elements.isEmpty();
    }
}
