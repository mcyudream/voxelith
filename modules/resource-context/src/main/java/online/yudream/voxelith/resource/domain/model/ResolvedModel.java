package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 继承链展开后的模型：贴图变量已全部解析为真实贴图 id，元素取自链上最近定义处。
 *
 * @param textures         变量名 → 最终贴图 id（不含未解析的 "#" 引用）
 * @param elements         有效元素列表
 * @param ambientOcclusion 是否启用 AO
 */
public record ResolvedModel(
        Identifier id,
        Map<String, Identifier> textures,
        List<ModelElement> elements,
        boolean ambientOcclusion) {

    /** 元素面上的 "#var" 引用 → 最终贴图。 */
    public Optional<Identifier> resolveTexture(String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        String key = reference.startsWith("#") ? reference.substring(1) : reference;
        return Optional.ofNullable(textures.get(key));
    }
}
