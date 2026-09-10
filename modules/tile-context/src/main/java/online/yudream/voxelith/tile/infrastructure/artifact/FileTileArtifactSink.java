package online.yudream.voxelith.tile.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.application.TileArtifactSink;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件系统落盘：hires → tiles/hires/{x}/{z}.glb，LOD → tiles/lod/{level}/{x}/{z}.glb，
 * 另有 atlas.png + tile-report.json。
 */
public final class FileTileArtifactSink implements TileArtifactSink {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public Path writeTile(Path outputDir, TilePos pos, byte[] glb) {
        String relative = pos.isHires()
                ? "tiles/hires/" + pos.x() + "/" + pos.z() + ".glb"
                : "tiles/lod/" + pos.level() + "/" + pos.x() + "/" + pos.z() + ".glb";
        return write(outputDir.resolve(relative), glb);
    }

    @Override
    public Path writeAtlas(Path outputDir, byte[] png) {
        return write(outputDir.resolve("atlas.png"), png);
    }

    @Override
    public Path writeAtlasLayout(Path outputDir, AtlasLayout layout) {
        return AtlasLayoutFiles.write(outputDir.resolve(AtlasLayoutFiles.FILE_NAME), layout);
    }

    @Override
    public Path writeReport(Path outputDir, List<TileOutcome.TileSummary> tiles,
                            int textureCount, int atlasSize) {
        JsonObject report = new JsonObject();
        report.addProperty("formatVersion", 1);
        report.addProperty("link", "tile");
        report.addProperty("tileCount", tiles.size());
        report.addProperty("textureCount", textureCount);
        report.addProperty("atlasSize", atlasSize);

        JsonArray tileArray = new JsonArray();
        for (TileOutcome.TileSummary tile : tiles) {
            JsonObject entry = new JsonObject();
            entry.addProperty("level", tile.pos().level());
            entry.addProperty("x", tile.pos().x());
            entry.addProperty("z", tile.pos().z());
            entry.addProperty("quads", tile.quads());
            entry.addProperty("vertices", tile.vertices());
            entry.addProperty("bytes", tile.bytes());
            entry.addProperty("sha1", tile.sha1());
            entry.add("min", floats(tile.min()));
            entry.add("max", floats(tile.max()));
            tileArray.add(entry);
        }
        report.add("tiles", tileArray);

        return write(outputDir.resolve("tile-report.json"),
                gson.toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray floats(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static Path write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("瓦片产物写入失败: " + file, e);
        }
        return file;
    }
}
