package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * atlas-layout.json 读写：全量 tile 落盘、发布复制、增量 PublishedAtlas 共用同一格式。
 */
public final class AtlasLayoutFiles {

    public static final String FILE_NAME = "atlas-layout.json";

    private static final Type CELL_INDEX = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AtlasLayoutFiles() {
    }

    public static Path write(Path file, AtlasLayout layout) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("cellSize", layout.cellSize());
            root.addProperty("cols", layout.cols());
            root.addProperty("width", layout.width());
            root.addProperty("height", layout.height());
            // 老读者（增量路径的历史版本）只认 pixelSize：写成宽度，正方形图集下语义一致
            root.addProperty("pixelSize", layout.width());
            root.add("cellIndex", GSON.toJsonTree(layout.cellIndex()));
            Files.writeString(file, GSON.toJson(root));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写图集布局失败: " + file, e);
        }
    }

    public static AtlasLayout read(Path file) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, Integer> cells = GSON.fromJson(root.get("cellIndex"), CELL_INDEX);
            // 兼容：老布局只有 pixelSize（正方形），新布局写 width/height
            int width = root.has("width") ? root.get("width").getAsInt()
                    : root.get("pixelSize").getAsInt();
            int height = root.has("height") ? root.get("height").getAsInt() : width;
            return new AtlasLayout(
                    root.get("cellSize").getAsInt(),
                    root.get("cols").getAsInt(),
                    width,
                    height,
                    cells);
        } catch (IOException e) {
            throw new UncheckedIOException("读图集布局失败: " + file, e);
        }
    }
}
