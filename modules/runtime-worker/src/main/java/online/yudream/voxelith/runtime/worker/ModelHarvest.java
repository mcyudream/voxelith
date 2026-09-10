package online.yudream.voxelith.runtime.worker;

import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.zip.GZIPOutputStream;

/**
 * BakedModel 采集器（ADR 0001 第二阶段）：在 worker 进程内驱动原版 ModelLoader
 * （mojmap ModelBakery）完成 blockstate 解析 + quad 烘焙，导出为 gzip NDJSON。
 *
 * <p>全部经反射访问游戏类（1.20.1 intermediary 名），本类无任何 MC 编译期依赖。
 * 纹理图集用 SpriteLoader 纯 CPU 拼图，不做 GL 上传。</p>
 *
 * <p>导出格式（每行一个 JSON 对象）：首行 meta，其后每方块一行：
 * {@code {"block":"minecraft:stone","states":[{"state":"","model":"minecraft:block/stone#...",
 * "quads":[{"cull":"up"|null,"face":"up","tint":-1,"shade":true,"tex":"minecraft:block/stone",
 * "pos":[12 个 0~16 浮点],"uv":[8 个 0~16 浮点]}]}]}}</p>
 */
public final class ModelHarvest {

    /** 导出结果计数。 */
    public record HarvestCounts(int blocks, int states, int quads) {
    }

    // ---- 1.20.1 intermediary 名表（经 yarn 1.20.1+build.10 反查验证）----
    private static final String C_IDENTIFIER = "net.minecraft.class_2960";
    private static final String C_ZIP_PACK = "net.minecraft.class_3258";
    private static final String C_RESOURCE_TYPE = "net.minecraft.class_3264";
    private static final String C_RM_IMPL = "net.minecraft.class_6861";
    private static final String C_RESOURCE_MANAGER = "net.minecraft.class_3300";
    private static final String C_BAKED_MODEL_MANAGER = "net.minecraft.class_1092";
    private static final String C_BLOCK_COLORS = "net.minecraft.class_324";
    private static final String C_DUMMY_PROFILER = "net.minecraft.class_3694";
    private static final String C_ATLAS = "net.minecraft.class_1059";
    private static final String C_SPRITE_LOADER = "net.minecraft.class_7766";
    private static final String C_STITCH_RESULT = "net.minecraft.class_7766$class_7767";
    private static final String C_SPRITE_ID = "net.minecraft.class_4730";
    private static final String C_SPRITE = "net.minecraft.class_1058";
    private static final String C_MODEL_LOADER = "net.minecraft.class_1088";
    private static final String C_REGISTRIES = "net.minecraft.class_7923";
    private static final String C_REGISTRY = "net.minecraft.class_2378";
    private static final String C_BLOCK = "net.minecraft.class_2248";
    private static final String C_STATE_MANAGER = "net.minecraft.class_2689";
    private static final String C_STATE = "net.minecraft.class_2688";
    private static final String C_PROPERTY = "net.minecraft.class_2769";
    private static final String C_DIRECTION = "net.minecraft.class_2350";
    private static final String C_RANDOM = "net.minecraft.class_5819";
    private static final String C_BAKED_MODEL = "net.minecraft.class_1087";
    private static final String C_BAKED_QUAD = "net.minecraft.class_777";
    private static final String C_BLOCK_MODELS = "net.minecraft.class_773";

    /**
     * 执行采集：要求 registries 已完成 bootstrap（见 {@link MinecraftBlockSmoke} 的前序调用）。
     *
     * @param gameClassLoader Knot 返回的游戏 ClassLoader
     * @param gameJar         MC client jar（同时作为资源 zip：内含 assets/minecraft/**）
     * @param outFile         导出文件（.json.gz）
     */
    public static HarvestCounts harvest(ClassLoader gameClassLoader, Path gameJar, Path outFile)
            throws Exception {
        Class<?> identifier = forName(gameClassLoader, C_IDENTIFIER);
        Class<?> resourceManager = forName(gameClassLoader, C_RESOURCE_MANAGER);
        Executor direct = Runnable::run;

        Object rm = buildResourceManager(gameClassLoader, identifier, gameJar);

        // 1) blockstate 定义 + 模型 JSON 两张输入表（BakedModelManager 的私有静态加载器）
        Class<?> bmm = forName(gameClassLoader, C_BAKED_MODEL_MANAGER);
        Method loadModels = bmm.getDeclaredMethod("method_45881", resourceManager, Executor.class);
        loadModels.setAccessible(true);
        Map<?, ?> models = (Map<?, ?>) ((CompletableFuture<?>) loadModels.invoke(null, rm, direct)).join();
        Method loadBlockStates = bmm.getDeclaredMethod("method_45896", resourceManager, Executor.class);
        loadBlockStates.setAccessible(true);
        Map<?, ?> blockStates =
                (Map<?, ?>) ((CompletableFuture<?>) loadBlockStates.invoke(null, rm, direct)).join();

        // 2) 方块图集 CPU 拼图 → SpriteIdentifier → Sprite 查询表
        Object blocksAtlasId = blocksAtlasId(gameClassLoader, identifier);
        // stitch 的第二参是图集定义 id（assets/<ns>/atlases/<path>.json），
        // 需由纹理 id "…:textures/atlas/blocks.png" 推出 "…:blocks"
        String atlasTex = blocksAtlasId.toString();
        String ns = atlasTex.substring(0, atlasTex.indexOf(':'));
        String atlasPath = atlasTex.substring(atlasTex.indexOf(':') + 1)
                .replaceFirst("^textures/atlas/", "").replaceFirst("\\.png$", "");
        Object atlasDefId = identifier.getConstructor(String.class, String.class)
                .newInstance(ns, atlasPath);
        Class<?> spriteLoaderClass = forName(gameClassLoader, C_SPRITE_LOADER);
        Object spriteLoader = spriteLoaderClass
                .getConstructor(identifier, int.class, int.class, int.class)
                .newInstance(blocksAtlasId, 8192, 8192, 8192);
        CompletableFuture<?> stitchFuture = (CompletableFuture<?>) spriteLoaderClass
                .getMethod("method_47661", resourceManager, identifier, int.class, Executor.class)
                .invoke(spriteLoader, rm, atlasDefId, 0, direct);
        Object stitch = stitchFuture.join();
        Class<?> stitchResult = forName(gameClassLoader, C_STITCH_RESULT);
        @SuppressWarnings("unchecked")
        Map<Object, Object> regions =
                (Map<Object, Object>) stitchResult.getMethod("comp_1044").invoke(stitch);
        Object missingSprite = stitchResult.getMethod("comp_1043").invoke(stitch);

        Method getTextureId = forName(gameClassLoader, C_SPRITE_ID).getMethod("method_24147");
        BiFunction<Object, Object, Object> textureGetter = (modelId, spriteId) -> {
            try {
                Object tex = getTextureId.invoke(spriteId);
                return regions.getOrDefault(tex, missingSprite);
            } catch (Exception e) {
                return missingSprite;
            }
        };
        Map<Object, String> spriteNames = new IdentityHashMap<>();
        regions.forEach((id, sprite) -> spriteNames.put(sprite, id.toString()));
        spriteNames.put(missingSprite, "minecraft:missingno");

        // 3) ModelLoader 构造 + 全量烘焙
        Object blockColors = forName(gameClassLoader, C_BLOCK_COLORS).getMethod("method_1689").invoke(null);
        Object profiler = forName(gameClassLoader, C_DUMMY_PROFILER).getField("field_16280").get(null);
        Class<?> modelLoaderClass = forName(gameClassLoader, C_MODEL_LOADER);
        Object modelLoader = modelLoaderClass
                .getConstructor(forName(gameClassLoader, C_BLOCK_COLORS),
                        forName(gameClassLoader, "net.minecraft.class_3695"), Map.class, Map.class)
                .newInstance(blockColors, profiler, models, blockStates);
        modelLoaderClass.getMethod("method_45876", BiFunction.class).invoke(modelLoader, textureGetter);
        Map<?, ?> bakedModels = (Map<?, ?>) modelLoaderClass.getMethod("method_4734").invoke(modelLoader);
        // 状态 → ModelIdentifier：与原版 BakedModelManager dispatch 一致
        // （BlockModels.method_3336(blockId, state)），不可用 ModelLoader.method_22820
        Method modelIdForState = forName(gameClassLoader, C_BLOCK_MODELS)
                .getMethod("method_3336", identifier, forName(gameClassLoader, "net.minecraft.class_2680"));

        // 4) 逐方块逐状态导出 quad
        return export(gameClassLoader, bakedModels, modelIdForState, spriteNames, outFile);
    }

    private static Object buildResourceManager(ClassLoader cl, Class<?> identifier, Path gameJar)
            throws Exception {
        Class<?> zipPack = forName(cl, C_ZIP_PACK);
        Object pack = zipPack.getConstructor(String.class, File.class, boolean.class)
                .newInstance("vanilla", gameJar.toFile(), false);
        Class<?> resourceType = forName(cl, C_RESOURCE_TYPE);
        Method directory = resourceType.getMethod("method_14413");
        Object clientResources = null;
        for (Object constant : resourceType.getEnumConstants()) {
            if ("assets".equals(directory.invoke(constant))) {
                clientResources = constant;
            }
        }
        if (clientResources == null) {
            throw new IllegalStateException("ResourceType 中找不到 assets 目录常量");
        }
        return forName(cl, C_RM_IMPL)
                .getConstructor(resourceType, List.class)
                .newInstance(clientResources, List.of(pack));
    }

    private static Object blocksAtlasId(ClassLoader cl, Class<?> identifier) throws Exception {
        for (Field f : forName(cl, C_ATLAS).getFields()) {
            if (f.getType() == identifier) {
                Object value = f.get(null);
                if (value != null && value.toString().endsWith("atlas/blocks.png")) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("找不到方块图集 id（textures/atlas/blocks.png）");
    }

    private static HarvestCounts export(ClassLoader cl, Map<?, ?> bakedModels,
                                        Method modelIdForState,
                                        Map<Object, String> spriteNames, Path outFile) throws Exception {
        Class<?> registry = forName(cl, C_REGISTRY);
        Method getId = registry.getMethod("method_10221", Object.class);
        Object blockRegistry = forName(cl, C_REGISTRIES).getField("field_41175").get(null);
        Method getStateManager = forName(cl, C_BLOCK).getMethod("method_9595");
        Method getStates = forName(cl, C_STATE_MANAGER).getMethod("method_11662");
        Method stateGetEntries = forName(cl, C_STATE).getMethod("method_11656");
        Class<?> property = forName(cl, C_PROPERTY);
        Method propertyName = property.getMethod("method_11899");
        Method propertyValueName = property.getMethod("method_11901", Comparable.class);
        Object[] directions = forName(cl, C_DIRECTION).getEnumConstants();
        Method directionName = forName(cl, C_DIRECTION).getMethod("method_10151");
        Object random = forName(cl, C_RANDOM).getMethod("method_43049", long.class).invoke(null, 42L);
        Class<?> blockState = forName(cl, "net.minecraft.class_2680");
        Class<?> direction = forName(cl, C_DIRECTION);
        Class<?> randomClass = forName(cl, C_RANDOM);
        Method getQuads = forName(cl, C_BAKED_MODEL)
                .getMethod("method_4707", blockState, direction, randomClass);
        Class<?> quad = forName(cl, C_BAKED_QUAD);
        Method quadVertexData = quad.getMethod("method_3357");
        Method quadTint = quad.getMethod("method_3359");
        Method quadShade = quad.getMethod("method_3360");
        Method quadFace = quad.getMethod("method_3358");
        Method quadSprite = quad.getMethod("method_35788");
        Class<?> sprite = forName(cl, C_SPRITE);
        Method spriteMinU = sprite.getMethod("method_4594");
        Method spriteMaxU = sprite.getMethod("method_4577");
        Method spriteMinV = sprite.getMethod("method_4593");
        Method spriteMaxV = sprite.getMethod("method_4575");

        int blocks = 0;
        int states = 0;
        int quadsTotal = 0;
        Files.createDirectories(outFile.toAbsolutePath().getParent());
        try (OutputStreamWriter out = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(outFile)), StandardCharsets.UTF_8)) {
            out.write("{\"format\":\"voxelith-models/1\",\"sprites\":"
                    + (spriteNames.size() - 1) + "}\n");

            for (Object block : (Iterable<?>) blockRegistry) {
                Object blockId = getId.invoke(blockRegistry, block);
                Object stateManager = getStateManager.invoke(block);
                @SuppressWarnings("unchecked")
                List<Object> allStates = (List<Object>) getStates.invoke(stateManager);

                StringWriter blockBuf = new StringWriter(1 << 16);
                JsonWriter blockJson = new JsonWriter(blockBuf);
                blockJson.beginObject();
                blockJson.name("block").value(String.valueOf(blockId));
                blockJson.name("states").beginArray();
                int blockQuads = 0;
                for (Object state : allStates) {
                    Object modelId = modelIdForState.invoke(null, blockId, state);
                    Object baked = bakedModels.get(modelId);
                    if (baked == null) {
                        continue;
                    }
                    blockJson.beginObject();
                    blockJson.name("state").value(stateString(state, stateGetEntries,
                            propertyName, propertyValueName));
                    blockJson.name("model").value(String.valueOf(modelId));
                    blockJson.name("quads").beginArray();
                    for (int d = 0; d <= directions.length; d++) {
                        Object dir = d == directions.length ? null : directions[d];
                        String cull = dir == null ? null : (String) directionName.invoke(dir);
                        List<?> quads = (List<?>) getQuads.invoke(baked, state, dir, random);
                        for (Object q : quads) {
                            writeQuad(blockJson, q, cull, quadVertexData, quadTint, quadShade,
                                    quadFace, quadSprite, directionName,
                                    spriteMinU, spriteMaxU, spriteMinV, spriteMaxV, spriteNames);
                            blockQuads++;
                        }
                    }
                    blockJson.endArray();
                    blockJson.endObject();
                    states++;
                }
                blockJson.endArray();
                blockJson.endObject();
                blockJson.flush();
                if (blockQuads > 0) {
                    out.write(blockBuf.toString());
                    out.write('\n');
                    blocks++;
                    quadsTotal += blockQuads;
                }
            }
        }
        return new HarvestCounts(blocks, states, quadsTotal);
    }

    private static String stateString(Object state, Method stateGetEntries,
                                      Method propertyName, Method propertyValueName) throws Exception {
        Map<?, ?> entries = (Map<?, ?>) stateGetEntries.invoke(state);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append((String) propertyName.invoke(e.getKey()))
                    .append('=')
                    .append((String) propertyValueName.invoke(e.getKey(), (Comparable<?>) e.getValue()));
        }
        return sb.toString();
    }

    private static void writeQuad(JsonWriter out, Object q, String cull,
                                  Method quadVertexData, Method quadTint, Method quadShade,
                                  Method quadFace, Method quadSprite, Method directionName,
                                  Method spriteMinU, Method spriteMaxU,
                                  Method spriteMinV, Method spriteMaxV,
                                  Map<Object, String> spriteNames) throws Exception {
        int[] vertexData = (int[]) quadVertexData.invoke(q);
        Object spr = quadSprite.invoke(q);
        float minU = (Float) spriteMinU.invoke(spr);
        float maxU = (Float) spriteMaxU.invoke(spr);
        float minV = (Float) spriteMinV.invoke(spr);
        float maxV = (Float) spriteMaxV.invoke(spr);
        int stride = vertexData.length / 4;
        out.beginObject();
        if (cull != null) {
            out.name("cull").value(cull);
        }
        out.name("face").value((String) directionName.invoke(quadFace.invoke(q)));
        out.name("tint").value((Integer) quadTint.invoke(q));
        out.name("shade").value((Boolean) quadShade.invoke(q));
        out.name("tex").value(spriteNames.getOrDefault(spr, "minecraft:missingno"));
        out.name("pos").beginArray();
        for (int v = 0; v < 4; v++) {
            for (int axis = 0; axis < 3; axis++) {
                out.value(Float.intBitsToFloat(vertexData[v * stride + axis]));
            }
        }
        out.endArray();
        out.name("uv").beginArray();
        for (int v = 0; v < 4; v++) {
            out.value(toTexCoord(Float.intBitsToFloat(vertexData[v * stride + 4]), minU, maxU));
            out.value(toTexCoord(Float.intBitsToFloat(vertexData[v * stride + 5]), minV, maxV));
        }
        out.endArray();
        out.endObject();
    }

    /** 图集绝对坐标 → 0~16 贴图局部坐标。 */
    private static float toTexCoord(float atlasCoord, float min, float max) {
        float span = max - min;
        if (Math.abs(span) < 1.0E-6f) {
            return 0.0f;
        }
        return (atlasCoord - min) / span * 16.0f;
    }

    private static Class<?> forName(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private ModelHarvest() {
    }
}
