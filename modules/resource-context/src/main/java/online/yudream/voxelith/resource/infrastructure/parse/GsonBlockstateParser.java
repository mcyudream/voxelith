package online.yudream.voxelith.resource.infrastructure.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.resource.domain.model.ModelVariant;
import online.yudream.voxelith.resource.domain.model.MultipartCase;
import online.yudream.voxelith.resource.domain.model.StateCondition;
import online.yudream.voxelith.resource.domain.parse.BlockstateParser;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * blockstate JSON 的 gson 解析实现。支持 variants 与 multipart 两种形态，
 * multipart when 支持属性合取、"a|b" 析取值与 OR 嵌套。
 */
public final class GsonBlockstateParser implements BlockstateParser {

    @Override
    public BlockstateDefinition parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("variants")) {
            return parseVariants(root.getAsJsonObject("variants"));
        }
        if (root.has("multipart")) {
            return parseMultipart(root.getAsJsonArray("multipart"));
        }
        throw new IllegalArgumentException("blockstate JSON 缺少 variants/multipart 字段");
    }

    private BlockstateDefinition parseVariants(JsonObject variants) {
        Map<String, List<ModelVariant>> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            map.put(entry.getKey(), parseVariantList(entry.getValue()));
        }
        return new BlockstateDefinition.Variants(map);
    }

    private BlockstateDefinition parseMultipart(JsonArray cases) {
        List<MultipartCase> list = new ArrayList<>();
        for (JsonElement element : cases) {
            JsonObject obj = element.getAsJsonObject();
            StateCondition when = obj.has("when")
                    ? parseCondition(obj.getAsJsonObject("when"))
                    : StateCondition.ALWAYS;
            list.add(new MultipartCase(when, parseVariantList(obj.get("apply"))));
        }
        return new BlockstateDefinition.Multipart(list);
    }

    private StateCondition parseCondition(JsonObject when) {
        if (when.has("OR")) {
            List<StateCondition> alternatives = new ArrayList<>();
            for (JsonElement sub : when.getAsJsonArray("OR")) {
                alternatives.add(parseCondition(sub.getAsJsonObject()));
            }
            return new StateCondition.AnyOf(alternatives);
        }
        if (when.has("AND")) {
            // MC 原生无 AND 关键字，部分 mod/数据包使用：把数组内条件合并为一次合取
            Map<String, Set<String>> merged = new LinkedHashMap<>();
            for (JsonElement sub : when.getAsJsonArray("AND")) {
                StateCondition cond = parseCondition(sub.getAsJsonObject());
                if (cond instanceof StateCondition.PropertySet props) {
                    props.acceptedValues().forEach((k, v) ->
                            merged.computeIfAbsent(k, key -> new LinkedHashSet<>()).addAll(v));
                } else {
                    throw new IllegalArgumentException("AND 内仅支持属性条件");
                }
            }
            return new StateCondition.PropertySet(merged);
        }
        Map<String, Set<String>> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
            Set<String> values = new LinkedHashSet<>();
            for (String value : entry.getValue().getAsString().split("\\|")) {
                values.add(value.trim());
            }
            accepted.put(entry.getKey(), values);
        }
        return new StateCondition.PropertySet(accepted);
    }

    private List<ModelVariant> parseVariantList(JsonElement element) {
        List<ModelVariant> list = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(parseVariant(item.getAsJsonObject()));
            }
        } else {
            list.add(parseVariant(element.getAsJsonObject()));
        }
        return list;
    }

    private ModelVariant parseVariant(JsonObject obj) {
        Identifier model = Identifier.parse(obj.get("model").getAsString());
        int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
        int y = obj.has("y") ? obj.get("y").getAsInt() : 0;
        boolean uvLock = obj.has("uvlock") && obj.get("uvlock").getAsBoolean();
        int weight = obj.has("weight") ? obj.get("weight").getAsInt() : 1;
        return new ModelVariant(model, x, y, uvLock, weight);
    }
}
