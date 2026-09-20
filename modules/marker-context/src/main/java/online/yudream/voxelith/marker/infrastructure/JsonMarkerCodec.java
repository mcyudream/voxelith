package online.yudream.voxelith.marker.infrastructure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.marker.application.MarkerCodecPort;
import online.yudream.voxelith.marker.domain.Marker;
import online.yudream.voxelith.marker.domain.MarkerSet;
import online.yudream.voxelith.marker.domain.MarkerStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * markers.json 编解码：与 {@code @yudream/voxelith-core} 的 {@code markerSetSchema} 逐字段对齐。
 *
 * <p>格式：</p>
 * <pre>
 * {
 *   "formatVersion": 1,
 *   "mapId": "swust-campus",
 *   "sets": [
 *     { "id": "landmarks", "label": "地标", "toggleable": true, "defaultHidden": false,
 *       "sorting": 0,
 *       "markers": [
 *         { "type": "poi", "id": "library", "label": "图书馆",
 *           "minDistance": 0, "maxDistance": 1.7976931348623157E308,
 *           "style": {...}, "position": {"x":1,"y":2,"z":3} }
 *       ] }
 *   ]
 * }
 * </pre>
 *
 * <p>未知类型/缺字段一律在读取时报错而不是跳过：标注文件是人写的，
 * 静默丢掉一个点比直接报错难查得多。</p>
 */
public final class JsonMarkerCodec implements MarkerCodecPort {

    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double DEFAULT_MAX_DISTANCE = Double.MAX_VALUE;

    @Override
    public String write(String mapId, List<MarkerSet> sets) {
        return GSON.toJson(toJson(mapId, sets));
    }

    @Override
    public List<MarkerSet> read(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    public static JsonObject toJson(String mapId, List<MarkerSet> sets) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("mapId", mapId);
        JsonArray array = new JsonArray();
        for (MarkerSet set : sets) {
            JsonObject setJson = new JsonObject();
            setJson.addProperty("id", set.id());
            setJson.addProperty("label", set.label());
            setJson.addProperty("toggleable", set.toggleable());
            setJson.addProperty("defaultHidden", set.hiddenByDefault());
            setJson.addProperty("sorting", set.sorting());
            JsonArray markers = new JsonArray();
            for (Marker marker : set.markers()) {
                markers.add(markerToJson(marker));
            }
            setJson.add("markers", markers);
            array.add(setJson);
        }
        root.add("sets", array);
        return root;
    }

    public static List<MarkerSet> fromJson(JsonObject root) {
        int version = root.has("formatVersion") ? root.get("formatVersion").getAsInt() : 0;
        if (version > FORMAT_VERSION) {
            throw new IllegalArgumentException("markers.json 版本高于当前实现: " + version);
        }
        List<MarkerSet> sets = new ArrayList<>();
        if (root.has("sets")) {
            for (JsonElement element : root.getAsJsonArray("sets")) {
                sets.add(setFromJson(element.getAsJsonObject()));
            }
        }
        return List.copyOf(sets);
    }

    private static MarkerSet setFromJson(JsonObject json) {
        List<Marker> markers = new ArrayList<>();
        if (json.has("markers")) {
            for (JsonElement element : json.getAsJsonArray("markers")) {
                markers.add(markerFromJson(element.getAsJsonObject()));
            }
        }
        return new MarkerSet(
                json.get("id").getAsString(),
                json.has("label") ? json.get("label").getAsString() : json.get("id").getAsString(),
                !json.has("toggleable") || json.get("toggleable").getAsBoolean(),
                json.has("defaultHidden") && json.get("defaultHidden").getAsBoolean(),
                json.has("sorting") ? json.get("sorting").getAsInt() : 0,
                markers);
    }

    private static JsonObject markerToJson(Marker marker) {
        JsonObject json = new JsonObject();
        json.addProperty("id", marker.id());
        json.addProperty("label", marker.label());
        json.addProperty("minDistance", marker.minDistance());
        json.addProperty("maxDistance", marker.maxDistance());
        json.add("style", styleToJson(marker.style()));
        switch (marker) {
            case Marker.Poi poi -> {
                json.addProperty("type", "poi");
                json.add("position", vec3(poi.position()));
                if (poi.detailHtml() != null) {
                    json.addProperty("detailHtml", poi.detailHtml());
                }
            }
            case Marker.Line line -> {
                json.addProperty("type", "line");
                JsonArray points = new JsonArray();
                line.points().forEach(point -> points.add(vec3(point)));
                json.add("points", points);
            }
            case Marker.Shape shape -> {
                json.addProperty("type", "shape");
                json.add("shape", ring(shape.shape()));
                json.add("holes", rings(shape.holes()));
                json.addProperty("shapeY", shape.shapeY());
            }
            case Marker.Extrude extrude -> {
                json.addProperty("type", "extrude");
                json.add("shape", ring(extrude.shape()));
                json.add("holes", rings(extrude.holes()));
                json.addProperty("shapeMinY", extrude.shapeMinY());
                json.addProperty("shapeMaxY", extrude.shapeMaxY());
            }
            case Marker.Box box -> {
                json.addProperty("type", "box");
                json.add("min", vec3(box.min()));
                json.add("max", vec3(box.max()));
            }
        }
        return json;
    }

    private static Marker markerFromJson(JsonObject json) {
        String id = json.get("id").getAsString();
        String label = json.has("label") ? json.get("label").getAsString() : id;
        MarkerStyle style = json.has("style")
                ? styleFromJson(json.getAsJsonObject("style")) : MarkerStyle.DEFAULT;
        double min = json.has("minDistance") ? json.get("minDistance").getAsDouble() : 0;
        double max = json.has("maxDistance") ? json.get("maxDistance").getAsDouble() : DEFAULT_MAX_DISTANCE;
        String type = json.has("type") ? json.get("type").getAsString() : "poi";
        return switch (type) {
            case "poi" -> new Marker.Poi(id, label, style, min, max,
                    vec3FromJson(json.getAsJsonObject("position")),
                    json.has("detailHtml") ? json.get("detailHtml").getAsString() : null);
            case "line" -> new Marker.Line(id, label, style, min, max,
                    pointsFromJson(json.getAsJsonArray("points")));
            case "shape" -> new Marker.Shape(id, label, style, min, max,
                    ringFromJson(json.getAsJsonArray("shape")),
                    holesFromJson(json), json.get("shapeY").getAsDouble());
            case "extrude" -> new Marker.Extrude(id, label, style, min, max,
                    ringFromJson(json.getAsJsonArray("shape")),
                    holesFromJson(json),
                    json.get("shapeMinY").getAsDouble(), json.get("shapeMaxY").getAsDouble());
            case "box" -> new Marker.Box(id, label, style, min, max,
                    vec3FromJson(json.getAsJsonObject("min")),
                    vec3FromJson(json.getAsJsonObject("max")));
            default -> throw new IllegalArgumentException("未知标注类型: " + type);
        };
    }

    private static JsonObject styleToJson(MarkerStyle style) {
        JsonObject json = new JsonObject();
        if (style.fillColor() != null) {
            json.addProperty("fillColor", style.fillColor());
        }
        if (style.lineColor() != null) {
            json.addProperty("lineColor", style.lineColor());
        }
        if (style.lineWidth() != null) {
            json.addProperty("lineWidth", style.lineWidth());
        }
        if (style.opacity() != null) {
            json.addProperty("opacity", style.opacity());
        }
        if (style.icon() != null) {
            json.addProperty("icon", style.icon());
        }
        if (style.depthTest() != null) {
            json.addProperty("depthTest", style.depthTest());
        }
        return json;
    }

    private static MarkerStyle styleFromJson(JsonObject json) {
        return new MarkerStyle(
                string(json, "fillColor"),
                string(json, "lineColor"),
                number(json, "lineWidth"),
                number(json, "opacity"),
                string(json, "icon"),
                json.has("depthTest") ? json.get("depthTest").getAsBoolean() : null);
    }

    private static JsonObject vec3(Marker.Vec3 value) {
        JsonObject json = new JsonObject();
        json.addProperty("x", value.x());
        json.addProperty("y", value.y());
        json.addProperty("z", value.z());
        return json;
    }

    private static Marker.Vec3 vec3FromJson(JsonObject json) {
        return new Marker.Vec3(
                json.get("x").getAsDouble(), json.get("y").getAsDouble(), json.get("z").getAsDouble());
    }

    /** 外环：{@code [{x,z}, ...]}。 */
    private static JsonArray ring(List<Marker.Vec2> points) {
        JsonArray array = new JsonArray();
        for (Marker.Vec2 point : points) {
            JsonObject json = new JsonObject();
            json.addProperty("x", point.x());
            json.addProperty("z", point.z());
            array.add(json);
        }
        return array;
    }

    private static JsonArray rings(List<List<Marker.Vec2>> holes) {
        JsonArray array = new JsonArray();
        for (List<Marker.Vec2> hole : holes) {
            array.add(ring(hole));
        }
        return array;
    }

    private static List<Marker.Vec2> ringFromJson(JsonArray array) {
        List<Marker.Vec2> points = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            points.add(new Marker.Vec2(json.get("x").getAsDouble(), json.get("z").getAsDouble()));
        }
        return List.copyOf(points);
    }

    private static List<List<Marker.Vec2>> holesFromJson(JsonObject json) {
        if (!json.has("holes")) {
            return List.of();
        }
        List<List<Marker.Vec2>> holes = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("holes")) {
            holes.add(ringFromJson(element.getAsJsonArray()));
        }
        return List.copyOf(holes);
    }

    private static List<Marker.Vec3> pointsFromJson(JsonArray array) {
        List<Marker.Vec3> points = new ArrayList<>();
        for (JsonElement element : array) {
            points.add(vec3FromJson(element.getAsJsonObject()));
        }
        return List.copyOf(points);
    }

    private static String string(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : null;
    }

    private static Double number(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsDouble() : null;
    }
}
