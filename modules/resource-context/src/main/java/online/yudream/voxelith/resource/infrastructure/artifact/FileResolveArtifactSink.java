package online.yudream.voxelith.resource.infrastructure.artifact;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import online.yudream.voxelith.resource.application.ResolveArtifactSink;
import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.resource.domain.model.ElementFace;
import online.yudream.voxelith.resource.domain.model.ModelElement;
import online.yudream.voxelith.resource.domain.model.ModelVariant;
import online.yudream.voxelith.resource.domain.model.MultipartCase;
import online.yudream.voxelith.resource.domain.model.ResolvedModel;
import online.yudream.voxelith.resource.domain.model.StateCondition;
import online.yudream.voxelith.resource.domain.registry.BlockModelRegistry;
import online.yudream.voxelith.resource.domain.registry.ResolveReport;
import online.yudream.voxelith.resource.domain.registry.ResolvedBlock;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * resolve 链路产物落盘实现：手写 JSON 树保证磁盘格式稳定、可读、可 diff（确认门核查用）。
 */
public final class FileResolveArtifactSink implements ResolveArtifactSink {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void writeRegistry(Path outputDir, BlockModelRegistry registry) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);

        JsonObject blocks = new JsonObject();
        for (Map.Entry<Identifier, ResolvedBlock> entry : registry.blocks().entrySet()) {
            blocks.add(entry.getKey().toString(), blockToJson(entry.getValue()));
        }
        root.add("blocks", blocks);

        JsonObject models = new JsonObject();
        for (Map.Entry<Identifier, ResolvedModel> entry : registry.models().entrySet()) {
            models.add(entry.getKey().toString(), modelToJson(entry.getValue()));
        }
        root.add("models", models);

        writeJson(outputDir.resolve("resolved-registry.json"), root);
    }

    @Override
    public void writeReport(Path outputDir, ResolveReport report) {
        JsonObject root = new JsonObject();
        root.addProperty("blocksFound", report.blocksFound());
        root.addProperty("blocksResolved", report.blocksResolved());
        root.addProperty("blockCoverage", report.blockCoverage());
        root.addProperty("modelsFound", report.modelsFound());
        root.addProperty("modelsResolved", report.modelsResolved());
        root.addProperty("modelCoverage", report.modelCoverage());
        root.add("unresolvedBlocks", toJsonArray(report.unresolvedBlocks()));
        root.add("missingModels", toJsonArray(report.missingModels()));
        root.add("missingTextures", toJsonArray(report.missingTextures()));
        writeJson(outputDir.resolve("resolve-report.json"), root);
    }

    @Override
    public int writeTextures(Path outputDir, Collection<Identifier> textures,
                             Function<Identifier, Optional<byte[]>> reader) {
        int exported = 0;
        for (Identifier texture : textures) {
            Optional<byte[]> bytes = reader.apply(texture);
            if (bytes.isEmpty()) {
                continue;
            }
            Path target = outputDir.resolve("textures")
                    .resolve(texture.namespace())
                    .resolve(texture.path() + ".png");
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes.get());
                exported++;
            } catch (IOException e) {
                throw new UncheckedIOException("导出贴图失败: " + texture, e);
            }
        }
        return exported;
    }

    private JsonObject blockToJson(ResolvedBlock block) {
        JsonObject json = new JsonObject();
        switch (block.definition()) {
            case BlockstateDefinition.Variants variants -> {
                json.addProperty("type", "variants");
                JsonObject variantsJson = new JsonObject();
                variants.variants().forEach((key, list) -> variantsJson.add(key, variantsToJson(list)));
                json.add("variants", variantsJson);
            }
            case BlockstateDefinition.Multipart multipart -> {
                json.addProperty("type", "multipart");
                JsonArray cases = new JsonArray();
                for (MultipartCase c : multipart.cases()) {
                    JsonObject caseJson = new JsonObject();
                    if (c.when() != null && c.when() != StateCondition.ALWAYS) {
                        caseJson.add("when", conditionToJson(c.when()));
                    }
                    caseJson.add("apply", variantsToJson(c.apply()));
                    cases.add(caseJson);
                }
                json.add("multipart", cases);
            }
        }
        JsonArray refs = new JsonArray();
        block.referencedModels().forEach(id -> refs.add(id.toString()));
        json.add("referencedModels", refs);
        return json;
    }

    private JsonObject conditionToJson(StateCondition condition) {
        JsonObject json = new JsonObject();
        switch (condition) {
            case StateCondition.PropertySet props ->
                    props.acceptedValues().forEach((prop, values) -> json.addProperty(prop, String.join("|", values)));
            case StateCondition.AnyOf anyOf -> {
                JsonArray alternatives = new JsonArray();
                anyOf.alternatives().forEach(sub -> alternatives.add(conditionToJson(sub)));
                json.add("OR", alternatives);
            }
            default -> throw new IllegalArgumentException("未知条件形态: " + condition.getClass());
        }
        return json;
    }

    private JsonArray variantsToJson(java.util.List<ModelVariant> variants) {
        JsonArray array = new JsonArray();
        for (ModelVariant variant : variants) {
            JsonObject json = new JsonObject();
            json.addProperty("model", variant.model().toString());
            if (variant.x() != 0) {
                json.addProperty("x", variant.x());
            }
            if (variant.y() != 0) {
                json.addProperty("y", variant.y());
            }
            if (variant.uvLock()) {
                json.addProperty("uvlock", true);
            }
            if (variant.weight() != 1) {
                json.addProperty("weight", variant.weight());
            }
            array.add(json);
        }
        return array;
    }

    private JsonObject modelToJson(ResolvedModel model) {
        JsonObject json = new JsonObject();
        JsonObject textures = new JsonObject();
        model.textures().forEach((var, id) -> textures.addProperty(var, id.toString()));
        json.add("textures", textures);
        json.addProperty("ambientOcclusion", model.ambientOcclusion());

        JsonArray elements = new JsonArray();
        for (ModelElement element : model.elements()) {
            elements.add(elementToJson(element));
        }
        json.add("elements", elements);
        return json;
    }

    private JsonObject elementToJson(ModelElement element) {
        JsonObject json = new JsonObject();
        json.add("from", vecToJson(element.from()));
        json.add("to", vecToJson(element.to()));
        if (element.rotation() != null) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", vecToJson(element.rotation().origin()));
            rotation.addProperty("axis", element.rotation().axis().name().toLowerCase());
            rotation.addProperty("angle", element.rotation().angle());
            if (element.rotation().rescale()) {
                rotation.addProperty("rescale", true);
            }
            json.add("rotation", rotation);
        }
        if (!element.shade()) {
            json.addProperty("shade", false);
        }
        JsonObject faces = new JsonObject();
        for (Map.Entry<Direction, ElementFace> entry : element.faces().entrySet()) {
            faces.add(entry.getKey().name().toLowerCase(), faceToJson(entry.getValue()));
        }
        json.add("faces", faces);
        return json;
    }

    private JsonObject faceToJson(ElementFace face) {
        JsonObject json = new JsonObject();
        if (face.uv() != null) {
            json.add("uv", vecToJson(face.uv()));
        }
        json.addProperty("texture", face.texture());
        if (face.cullface() != null) {
            json.addProperty("cullface", face.cullface().name().toLowerCase());
        }
        if (face.rotation() != 0) {
            json.addProperty("rotation", face.rotation());
        }
        if (face.tintIndex() >= 0) {
            json.addProperty("tintindex", face.tintIndex());
        }
        return json;
    }

    private static JsonArray vecToJson(float[] vec) {
        JsonArray array = new JsonArray();
        for (float v : vec) {
            array.add(v);
        }
        return array;
    }

    private static JsonArray toJsonArray(java.util.List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void writeJson(Path target, JsonObject json) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(json));
        } catch (IOException e) {
            throw new UncheckedIOException("写入产物失败: " + target, e);
        }
    }
}
