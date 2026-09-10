package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已发布图集落盘：{publishRoot}/{mapId}/atlas.png + atlas-layout.json。
 * 增量重跑只读这两份文件，不重打包。
 */
public final class FilePublishedAtlas implements PublishedAtlas {

    private static final Type CELL_INDEX = new TypeToken<LinkedHashMap<String, Integer>>() { }.getType();
    private final Path publishRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FilePublishedAtlas(Path publishRoot) {
        this.publishRoot = publishRoot;
    }

    @Override
    public Optional<AtlasReuse> load(String mapId) {
        Path mapDir = publishRoot.resolve(mapId);
        Path pngFile = mapDir.resolve("atlas.png");
        Path layoutFile = mapDir.resolve("atlas-layout.json");
        if (!Files.isRegularFile(pngFile) || !Files.isRegularFile(layoutFile)) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(layoutFile)).getAsJsonObject();
            Map<String, Integer> cells = gson.fromJson(root.get("cellIndex"), CELL_INDEX);
            AtlasLayout layout = new AtlasLayout(
                    root.get("cellSize").getAsInt(),
                    root.get("cols").getAsInt(),
                    root.get("pixelSize").getAsInt(),
                    cells);
            return Optional.of(new AtlasReuse(layout, Files.readAllBytes(pngFile)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取已发布图集失败: " + mapDir, e);
        }
    }

    @Override
    public void save(String mapId, AtlasResult atlas, byte[] png) {
        Path mapDir = publishRoot.resolve(mapId);
        try {
            Files.createDirectories(mapDir);
            Files.write(mapDir.resolve("atlas.png"), png);
            JsonObject root = new JsonObject();
            root.addProperty("cellSize", atlas.layout().cellSize());
            root.addProperty("cols", atlas.layout().cols());
            root.addProperty("pixelSize", atlas.layout().pixelSize());
            root.add("cellIndex", gson.toJsonTree(atlas.layout().cellIndex()));
            Files.writeString(mapDir.resolve("atlas-layout.json"), gson.toJson(root));
        } catch (IOException e) {
            throw new UncheckedIOException("写已发布图集失败: " + mapDir, e);
        }
    }
}
