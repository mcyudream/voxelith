package online.yudream.voxelith.resource.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * blockstate 状态匹配领域服务：给定方块状态属性（属性名 → 属性值），选出应渲染的模型引用。
 * <p>
 * variants 形态：键是 "prop=value,prop2=value2" 子集匹配，取最具体（属性对最多）的一组；
 * multipart 形态：所有命中 case 的 apply 叠加。
 */
public final class BlockstateResolver {

    /**
     * 选出应渲染的模型组：每组内部是加权随机的候选（取其一），组与组之间叠加渲染。
     * variants 形态至多返回一组；multipart 形态每个命中 case 贡献一组。
     */
    public List<List<ModelVariant>> select(BlockstateDefinition definition, Map<String, String> state) {
        if (definition instanceof BlockstateDefinition.Variants variants) {
            List<ModelVariant> best = selectFromVariants(variants.variants(), state);
            return best.isEmpty() ? List.of() : List.of(best);
        }
        if (definition instanceof BlockstateDefinition.Multipart multipart) {
            List<List<ModelVariant>> groups = new ArrayList<>();
            for (MultipartCase c : multipart.cases()) {
                if (c.when() == null || c.when().matches(state)) {
                    groups.add(c.apply());
                }
            }
            return groups;
        }
        throw new IllegalArgumentException("未知 blockstate 定义形态: " + definition.getClass());
    }

    private List<ModelVariant> selectFromVariants(Map<String, List<ModelVariant>> variants, Map<String, String> state) {
        List<ModelVariant> best = null;
        int bestSpecificity = -1;
        for (Map.Entry<String, List<ModelVariant>> entry : variants.entrySet()) {
            Map<String, String> key = parseKey(entry.getKey());
            if (!matchesAll(key, state)) {
                continue;
            }
            if (key.size() > bestSpecificity) {
                bestSpecificity = key.size();
                best = entry.getValue();
            }
        }
        return best == null ? List.of() : best;
    }

    static Map<String, String> parseKey(String key) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (key == null || key.isBlank()) {
            return pairs;
        }
        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                pairs.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return pairs;
    }

    private static boolean matchesAll(Map<String, String> key, Map<String, String> state) {
        for (Map.Entry<String, String> pair : key.entrySet()) {
            if (!pair.getValue().equals(state.get(pair.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
