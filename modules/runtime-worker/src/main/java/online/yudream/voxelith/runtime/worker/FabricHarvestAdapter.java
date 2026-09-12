package online.yudream.voxelith.runtime.worker;

import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.zip.GZIPOutputStream;

/**
 * 内置 Fabric 采集适配器：用 intermediary 类名 + 方法签名探测覆盖 1.20.1–1.20.4。
 * 不写死单一 DataVersion；ZipResourcePack 按签名探测；fabric-model-loading-api 的 CURRENT_PLUGINS 在无头路径自行注入。
 */
public final class FabricHarvestAdapter implements HarvestAdapter {

    public static final int MIN_EXPECTED_BLOCKS = 900;

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
    private static final String C_MODEL_PLUGIN_MANAGER =
            "net.fabricmc.fabric.impl.client.model.loading.ModelLoadingPluginManager";
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
    private static final String C_ZIP_WRAPPER = "net.minecraft.class_3258$class_8616";
    private static final String C_ZIP_FACTORY = "net.minecraft.class_3258$class_8615";

    @Override
    public String id() {
        return "fabric-intermediary";
    }

    @Override
    public boolean supports(ClassLoader gameClassLoader) {
        return Reflect.present(gameClassLoader, "net.minecraft.class_2246")
                && Reflect.present(gameClassLoader, C_MODEL_LOADER)
                && Reflect.present(gameClassLoader, C_BAKED_MODEL_MANAGER)
                && Reflect.present(gameClassLoader, C_ZIP_PACK);
    }

    @Override
    public int bootstrapRegistries(ClassLoader gameClassLoader) throws Exception {
        Class<?> worldVersionClass = Reflect.init(gameClassLoader, "net.minecraft.class_6489");
        Object version = Reflect.init(gameClassLoader, "net.minecraft.class_3797")
                .getMethod("method_16672")
                .invoke(null);
        Reflect.init(gameClassLoader, "net.minecraft.class_155")
                .getMethod("method_34872", worldVersionClass)
                .invoke(null, version);
        Reflect.init(gameClassLoader, "net.minecraft.class_2966")
                .getMethod("method_12851")
                .invoke(null);
        invokeFabricModEntrypoints(gameClassLoader);
        return HarvestAdapters.countRegisteredBlocks(gameClassLoader);
    }

    /**
     * Knot.init 只完成加载器 freeze，不跑 {@code Hooks.startClient}。
     * 无头路径在 Bootstrap 之后自行 invoke main/client 入口，让模组把方块注册进 Registries.BLOCK。
     */
    private static void invokeFabricModEntrypoints(ClassLoader cl) throws Exception {
        Class<?> hooks = Class.forName("net.fabricmc.loader.impl.game.minecraft.Hooks", true, cl);
        File runDir = new File(".");
        try {
            hooks.getMethod("startClient", File.class, Object.class).invoke(null, runDir, null);
            return;
        } catch (InvocationTargetException e) {
            // 部分入口会碰到 MinecraftClient 未构造；退回只跑 main
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("Hooks.startClient 失败，改跑 main 入口: " + cause);
        }
        Class<?> loaderApi = Class.forName("net.fabricmc.loader.api.FabricLoader", true, cl);
        Object loader = loaderApi.getMethod("getInstance").invoke(null);
        Class<?> modInit = Class.forName("net.fabricmc.api.ModInitializer", true, cl);
        java.util.function.Consumer<Object> onInitialize = entry -> {
            try {
                modInit.getMethod("onInitialize").invoke(entry);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        };
        loaderApi.getMethod("invokeEntrypoints", String.class, Class.class, java.util.function.Consumer.class)
                .invoke(loader, "main", modInit, onInitialize);
    }

    @Override
    public ModelHarvest.HarvestCounts harvest(ClassLoader gameClassLoader, Path gameJar,
                                              List<Path> modJars, Path outFile) throws Exception {
        Class<?> identifier = Reflect.cls(gameClassLoader, C_IDENTIFIER);
        Class<?> resourceManager = Reflect.cls(gameClassLoader, C_RESOURCE_MANAGER);
        Executor direct = Runnable::run;

        Object rm = buildResourceManager(gameClassLoader, gameJar, modJars);

        Class<?> bmm = Reflect.cls(gameClassLoader, C_BAKED_MODEL_MANAGER);
        Method loadModels = Reflect.declared(bmm, "method_45881", resourceManager, Executor.class);
        Map<?, ?> models = (Map<?, ?>) ((CompletableFuture<?>) loadModels.invoke(null, rm, direct)).join();
        Method loadBlockStates = Reflect.declared(bmm, "method_45896", resourceManager, Executor.class);
        Map<?, ?> blockStates =
                (Map<?, ?>) ((CompletableFuture<?>) loadBlockStates.invoke(null, rm, direct)).join();

        Object blocksAtlasId = blocksAtlasId(gameClassLoader, identifier);
        String atlasTex = blocksAtlasId.toString();
        String ns = atlasTex.substring(0, atlasTex.indexOf(':'));
        String atlasPath = atlasTex.substring(atlasTex.indexOf(':') + 1)
                .replaceFirst("^textures/atlas/", "").replaceFirst("\\.png$", "");
        Object atlasDefId = identifier.getConstructor(String.class, String.class)
                .newInstance(ns, atlasPath);
        Class<?> spriteLoaderClass = Reflect.cls(gameClassLoader, C_SPRITE_LOADER);
        Object spriteLoader = spriteLoaderClass
                .getConstructor(identifier, int.class, int.class, int.class)
                .newInstance(blocksAtlasId, 8192, 8192, 8192);
        Object stitch = stitch(spriteLoaderClass, spriteLoader, rm, atlasDefId, identifier, resourceManager, direct);
        Class<?> stitchResult = Reflect.cls(gameClassLoader, C_STITCH_RESULT);
        @SuppressWarnings("unchecked")
        Map<Object, Object> regions =
                (Map<Object, Object>) stitchResult.getMethod("comp_1044").invoke(stitch);
        Object missingSprite = stitchResult.getMethod("comp_1043").invoke(stitch);

        Method getTextureId = Reflect.cls(gameClassLoader, C_SPRITE_ID).getMethod("method_24147");
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

        Object blockColors = Reflect.cls(gameClassLoader, C_BLOCK_COLORS).getMethod("method_1689").invoke(null);
        Object profiler = Reflect.cls(gameClassLoader, C_DUMMY_PROFILER).getField("field_16280").get(null);
        seedModelLoadingPlugins(gameClassLoader, rm, direct);
        Class<?> modelLoaderClass = Reflect.cls(gameClassLoader, C_MODEL_LOADER);
        Object modelLoader = modelLoaderClass
                .getConstructor(Reflect.cls(gameClassLoader, C_BLOCK_COLORS),
                        Reflect.cls(gameClassLoader, "net.minecraft.class_3695"), Map.class, Map.class)
                .newInstance(blockColors, profiler, models, blockStates);
        modelLoaderClass.getMethod("method_45876", BiFunction.class).invoke(modelLoader, textureGetter);
        Map<?, ?> bakedModels = (Map<?, ?>) modelLoaderClass.getMethod("method_4734").invoke(modelLoader);
        Method modelIdForState = Reflect.cls(gameClassLoader, C_BLOCK_MODELS)
                .getMethod("method_3336", identifier, Reflect.cls(gameClassLoader, "net.minecraft.class_2680"));

        return export(gameClassLoader, bakedModels, modelIdForState, spriteNames, outFile);
    }

    /**
     * fabric-model-loading-api 在 ModelLoader 构造里读 CURRENT_PLUGINS；
     * 游戏客户端由 BakedModelManager.reload mixin 写入。无头路径自己 prepare + set。
     */
    private static void seedModelLoadingPlugins(ClassLoader cl, Object rm, Executor direct) throws Exception {
        if (!Reflect.present(cl, C_MODEL_PLUGIN_MANAGER)) {
            return;
        }
        Class<?> manager = Reflect.cls(cl, C_MODEL_PLUGIN_MANAGER);
        @SuppressWarnings("unchecked")
        ThreadLocal<List<Object>> current =
                (ThreadLocal<List<Object>>) Reflect.field(manager, "CURRENT_PLUGINS").get(null);
        Method prepare = manager.getMethod(
                "preparePlugins", Reflect.cls(cl, C_RESOURCE_MANAGER), Executor.class);
        @SuppressWarnings("unchecked")
        List<Object> plugins = (List<Object>) ((CompletableFuture<?>) prepare.invoke(null, rm, direct)).join();
        current.set(plugins == null ? List.of() : plugins);
    }

    private static Object stitch(Class<?> spriteLoaderClass, Object spriteLoader, Object rm,
                                 Object atlasDefId, Class<?> identifier, Class<?> resourceManager,
                                 Executor direct) throws Exception {
        // 1.20.1: method_47661 四参；1.20.4: method_52849 四参，或 method_47661 五参
        try {
            Method four = spriteLoaderClass.getMethod(
                    "method_47661", resourceManager, identifier, int.class, Executor.class);
            return ((CompletableFuture<?>) four.invoke(spriteLoader, rm, atlasDefId, 0, direct)).join();
        } catch (NoSuchMethodException ignored) {
            try {
                Method fourAlt = spriteLoaderClass.getMethod(
                        "method_52849", resourceManager, identifier, int.class, Executor.class);
                return ((CompletableFuture<?>) fourAlt.invoke(spriteLoader, rm, atlasDefId, 0, direct)).join();
            } catch (NoSuchMethodException ignoredAgain) {
                Method five = spriteLoaderClass.getMethod(
                        "method_47661", resourceManager, identifier, int.class, Executor.class, java.util.Collection.class);
                return ((CompletableFuture<?>) five.invoke(
                        spriteLoader, rm, atlasDefId, 0, direct, List.of())).join();
            }
        }
    }

    private static Object buildResourceManager(ClassLoader cl, Path gameJar, List<Path> extraJars) throws Exception {
        List<Object> packs = new ArrayList<>();
        packs.add(openZipPack(cl, "vanilla", gameJar.toFile()));
        int i = 0;
        for (Path extra : extraJars) {
            packs.add(openZipPack(cl, "mod-" + i++, extra.toFile()));
        }
        Class<?> resourceType = Reflect.cls(cl, C_RESOURCE_TYPE);
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
        Class<?> listType = List.class;
        return Reflect.cls(cl, C_RM_IMPL)
                .getConstructor(resourceType, listType)
                .newInstance(clientResources, packs);
    }

    /**
     * 按签名探测，不绑版本号：
     * <ul>
     *   <li>公开三参 {@code ZipResourcePack(String, File, boolean)}（1.20.1）</li>
     *   <li>公开工厂 {@code ZipFileFactory(File, boolean).open(String)}（1.20.4+）</li>
     *   <li>包可见 {@code ZipFileWrapper(File)} + 四参 ZipResourcePack</li>
     * </ul>
     */
    static Object openZipPack(ClassLoader cl, String name, File file) throws Exception {
        Class<?> zipPack = Reflect.cls(cl, C_ZIP_PACK);
        try {
            return Reflect.ctor(zipPack, String.class, File.class, boolean.class)
                    .newInstance(name, file, false);
        } catch (NoSuchMethodException ignored) {
            if (Reflect.present(cl, C_ZIP_FACTORY)) {
                Class<?> factory = Reflect.cls(cl, C_ZIP_FACTORY);
                Object provider = Reflect.ctor(factory, File.class, boolean.class)
                        .newInstance(file, false);
                return factory.getMethod("method_52424", String.class).invoke(provider, name);
            }
            Class<?> wrapper = Reflect.cls(cl, C_ZIP_WRAPPER);
            Object zipFile = Reflect.ctor(wrapper, File.class).newInstance(file);
            return Reflect.ctor(zipPack, String.class, wrapper, boolean.class, String.class)
                    .newInstance(name, zipFile, false, "");
        }
    }

    private static Object blocksAtlasId(ClassLoader cl, Class<?> identifier) throws Exception {
        for (Field f : Reflect.cls(cl, C_ATLAS).getFields()) {
            if (f.getType() == identifier) {
                Object value = f.get(null);
                if (value != null && value.toString().endsWith("atlas/blocks.png")) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("找不到方块图集 id（textures/atlas/blocks.png）");
    }

    private static ModelHarvest.HarvestCounts export(ClassLoader cl, Map<?, ?> bakedModels,
                                                     Method modelIdForState,
                                                     Map<Object, String> spriteNames, Path outFile) throws Exception {
        Class<?> registry = Reflect.cls(cl, C_REGISTRY);
        Method getId = registry.getMethod("method_10221", Object.class);
        Object blockRegistry = Reflect.cls(cl, C_REGISTRIES).getField("field_41175").get(null);
        Method getStateManager = Reflect.cls(cl, C_BLOCK).getMethod("method_9595");
        Method getStates = Reflect.cls(cl, C_STATE_MANAGER).getMethod("method_11662");
        Method stateGetEntries = Reflect.cls(cl, C_STATE).getMethod("method_11656");
        Class<?> property = Reflect.cls(cl, C_PROPERTY);
        Method propertyName = property.getMethod("method_11899");
        Method propertyValueName = property.getMethod("method_11901", Comparable.class);
        Object[] directions = Reflect.cls(cl, C_DIRECTION).getEnumConstants();
        Method directionName = Reflect.cls(cl, C_DIRECTION).getMethod("method_10151");
        Object random = Reflect.cls(cl, C_RANDOM).getMethod("method_43049", long.class).invoke(null, 42L);
        Class<?> blockState = Reflect.cls(cl, "net.minecraft.class_2680");
        Class<?> direction = Reflect.cls(cl, C_DIRECTION);
        Class<?> randomClass = Reflect.cls(cl, C_RANDOM);
        Method getQuads = Reflect.cls(cl, C_BAKED_MODEL)
                .getMethod("method_4707", blockState, direction, randomClass);
        Class<?> quad = Reflect.cls(cl, C_BAKED_QUAD);
        Method quadVertexData = quad.getMethod("method_3357");
        Method quadTint = quad.getMethod("method_3359");
        Method quadShade = quad.getMethod("method_3360");
        Method quadFace = quad.getMethod("method_3358");
        Method quadSprite = quad.getMethod("method_35788");
        Class<?> sprite = Reflect.cls(cl, C_SPRITE);
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

                java.io.StringWriter blockBuf = new java.io.StringWriter(1 << 16);
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
        return new ModelHarvest.HarvestCounts(blocks, states, quadsTotal);
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
                out.value(toModelPos(Float.intBitsToFloat(vertexData[v * stride + axis])));
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

    /**
     * BakedQuad 顶点在方块空间 0~1；协议 {@code pos} 为模型空间 0~16。
     * bake 侧还会再 /16 落到世界坐标，这里必须先乘回去。
     */
    static float toModelPos(float baked) {
        return baked * 16.0f;
    }

    private static float toTexCoord(float atlasCoord, float min, float max) {
        float span = max - min;
        if (Math.abs(span) < 1.0E-6f) {
            return 0.0f;
        }
        return (atlasCoord - min) / span * 16.0f;
    }
}
