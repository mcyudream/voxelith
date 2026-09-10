package online.yudream.voxelith.resource.infrastructure.parse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.resource.domain.model.ElementFace;
import online.yudream.voxelith.resource.domain.model.ElementRotation;
import online.yudream.voxelith.resource.domain.model.ModelDefinition;
import online.yudream.voxelith.resource.domain.model.ModelElement;
import online.yudream.voxelith.resource.domain.parse.ModelParser;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型 JSON 的 gson 解析实现：parent / textures / ambientocclusion / elements。
 * display/gui_light 等仅物品展示相关的字段忽略。
 */
public final class GsonModelParser implements ModelParser {

    @Override
    public ModelDefinition parse(Identifier id, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Identifier parent = root.has("parent") ? Identifier.parse(root.get("parent").getAsString()) : null;

        Map<String, String> textures = new LinkedHashMap<>();
        if (root.has("textures")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                textures.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        Boolean ambientOcclusion = root.has("ambientocclusion")
                ? root.get("ambientocclusion").getAsBoolean()
                : null;

        List<ModelElement> elements = new ArrayList<>();
        if (root.has("elements")) {
            for (JsonElement element : root.getAsJsonArray("elements")) {
                elements.add(parseElement(element.getAsJsonObject()));
            }
        }

        return new ModelDefinition(id, parent, textures, elements, ambientOcclusion);
    }

    private ModelElement parseElement(JsonObject obj) {
        float[] from = parseVec3(obj.getAsJsonArray("from"));
        float[] to = parseVec3(obj.getAsJsonArray("to"));

        ElementRotation rotation = null;
        if (obj.has("rotation")) {
            JsonObject rot = obj.getAsJsonObject("rotation");
            rotation = new ElementRotation(
                    parseVec3(rot.getAsJsonArray("origin")),
                    ElementRotation.Axis.byName(rot.get("axis").getAsString()),
                    rot.get("angle").getAsFloat(),
                    rot.has("rescale") && rot.get("rescale").getAsBoolean());
        }

        boolean shade = !obj.has("shade") || obj.get("shade").getAsBoolean();

        Map<Direction, ElementFace> faces = new EnumMap<>(Direction.class);
        if (obj.has("faces")) {
            for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("faces").entrySet()) {
                faces.put(Direction.byName(entry.getKey()), parseFace(entry.getValue().getAsJsonObject()));
            }
        }
        return new ModelElement(from, to, rotation, shade, faces);
    }

    private ElementFace parseFace(JsonObject obj) {
        float[] uv = obj.has("uv") ? parseVec4(obj.getAsJsonArray("uv")) : null;
        String texture = obj.get("texture").getAsString();
        // 游戏对未知 cullface 值宽容（原版 scaffolding_unstable 就写了 "bottom"），按无剔除处理
        Direction cullface = obj.has("cullface") ? parseDirectionLenient(obj.get("cullface").getAsString()) : null;
        int rotation = obj.has("rotation") ? obj.get("rotation").getAsInt() : 0;
        int tintIndex = obj.has("tintindex") ? obj.get("tintindex").getAsInt() : -1;
        return new ElementFace(uv, texture, cullface, rotation, tintIndex);
    }

    private static Direction parseDirectionLenient(String name) {
        try {
            return Direction.byName(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static float[] parseVec3(JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] parseVec4(JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }
}
