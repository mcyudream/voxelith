package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.parse.ModelParser;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型继承解析领域服务：沿 parent 链合并贴图变量表（子覆盖父）、
 * 取链上最近的 elements 定义、把 "#var" 引用解析为最终贴图 id。
 * 单次会话内带缓存；检测循环继承。
 */
public final class ModelResolver {

    private final PackStack packs;
    private final ModelParser parser;
    private final Map<Identifier, ResolvedModel> cache = new HashMap<>();

    public ModelResolver(PackStack packs, ModelParser parser) {
        this.packs = packs;
        this.parser = parser;
    }

    public ResolvedModel resolve(Identifier modelId) {
        ResolvedModel cached = cache.get(modelId);
        if (cached != null) {
            return cached;
        }
        ChainResult chain = resolveChain(modelId, new ArrayDeque<>());
        // 引用解析必须在完整合并表上进行（"#var" 可能跨越多层继承才落到最终贴图）
        ResolvedModel resolved = new ResolvedModel(
                modelId,
                resolveTextureRefs(chain.textures()),
                List.copyOf(chain.elements()),
                chain.ambientOcclusion() == null || chain.ambientOcclusion());
        cache.put(modelId, resolved);
        return resolved;
    }

    private record ChainResult(Map<String, String> textures, List<ModelElement> elements,
                               Boolean ambientOcclusion) {
    }

    private ChainResult resolveChain(Identifier modelId, Deque<Identifier> chain) {
        if (chain.contains(modelId)) {
            throw ModelResolutionException.cycle(describeChain(chain, modelId));
        }
        ModelDefinition def = load(modelId);
        chain.addLast(modelId);

        // 自顶向下合并：先取父链结果，再用本层覆盖。
        Map<String, String> mergedTextures;
        List<ModelElement> elements;
        Boolean ambientOcclusion;
        if (def.parent() != null) {
            ChainResult parent = resolveChain(def.parent(), chain);
            mergedTextures = new LinkedHashMap<>(parent.textures());
            elements = def.hasElements() ? def.elements() : parent.elements();
            ambientOcclusion = def.ambientOcclusion() != null ? def.ambientOcclusion() : parent.ambientOcclusion();
        } else {
            mergedTextures = new LinkedHashMap<>();
            elements = def.elements() == null ? List.of() : def.elements();
            ambientOcclusion = def.ambientOcclusion();
        }
        if (def.textures() != null) {
            mergedTextures.putAll(def.textures());
        }
        chain.removeLast();

        return new ChainResult(mergedTextures, elements, ambientOcclusion);
    }

    /** 把 "#var" 链式引用解析到最终贴图 id；未解析成功的引用被丢弃并视为缺失（由调用方记入报告）。 */
    private Map<String, Identifier> resolveTextureRefs(Map<String, String> raw) {
        Map<String, Identifier> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String value = entry.getValue();
            int guard = 0;
            while (value != null && value.startsWith("#") && guard++ < 16) {
                value = raw.get(value.substring(1));
            }
            if (value != null && !value.startsWith("#")) {
                resolved.put(entry.getKey(), Identifier.parse(value));
            }
        }
        return resolved;
    }

    private ModelDefinition load(Identifier modelId) {
        String json = packs.model(modelId)
                .orElseThrow(() -> ModelResolutionException.missing(modelId.toString()))
                .asUtf8();
        return parser.parse(modelId, json);
    }

    private static String describeChain(Deque<Identifier> chain, Identifier repeat) {
        List<String> parts = new ArrayList<>();
        chain.forEach(id -> parts.add(id.toString()));
        parts.add(repeat.toString());
        return String.join(" -> ", parts);
    }
}
