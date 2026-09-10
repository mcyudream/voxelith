package online.yudream.voxelith.runtime.infrastructure.fallback;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.runtime.application.ModelAcquisition;
import online.yudream.voxelith.runtime.application.ModelAcquisitionSink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * model-acquisition.json 落盘实现：标记模型来源（runtime 采集 / 静态兜底），
 * 兜底时附 runtime 失败明细，供管线确认门与排障核查。
 */
public final class JsonModelAcquisitionSink implements ModelAcquisitionSink {

    public static final String FILE_NAME = "model-acquisition.json";

    @Override
    public void write(Path workDir, ModelAcquisition acquisition) {
        JsonObject json = new JsonObject();
        json.addProperty("source", acquisition.source().name());
        json.addProperty("ok", acquisition.ok());
        json.addProperty("statesExported", acquisition.statesExported());
        json.addProperty("quadsExported", acquisition.quadsExported());
        json.addProperty("blocksResolved", acquisition.blocksResolved());
        json.addProperty("blocksFound", acquisition.blocksFound());
        JsonArray failures = new JsonArray();
        acquisition.runtimeFailures().forEach(failures::add);
        json.add("runtimeFailures", failures);
        json.addProperty("artifactPath", acquisition.artifactPath().toAbsolutePath().toString());
        try {
            Files.createDirectories(workDir);
            Files.writeString(workDir.resolve(FILE_NAME), json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写 model-acquisition.json 失败", e);
        }
    }
}
