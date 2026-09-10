package online.yudream.voxelith.runtime.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * headless worker 子进程入口（ADR 0001）。
 * 协议：父进程传 --spec &lt;path&gt;；worker 在 spec 同目录写 worker-result.json，
 * 退出码 0=成功。骨架期运行 LWJGL stub 自检；真实 BakedModel 采集后续接入。
 *
 * <p>本类只依赖本模块与 gson——worker JVM 不加载任何服务端类。</p>
 */
public final class HarvestWorkerMain {

    private static final String RESULT_FILE = "worker-result.json";

    public static void main(String[] args) {
        long start = System.nanoTime();
        Path specFile = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--spec".equals(args[i])) {
                specFile = Path.of(args[i + 1]);
            }
        }
        if (specFile == null) {
            System.err.println("缺少 --spec <path> 参数");
            System.exit(2);
        }
        Path workDir = specFile.toAbsolutePath().getParent();

        Map<String, Boolean> checks = new LinkedHashMap<>();
        JsonArray failures = new JsonArray();
        JsonObject spec = null;
        try {
            spec = JsonParser.parseString(
                    Files.readString(specFile, StandardCharsets.UTF_8)).getAsJsonObject();
            checks.put("spec.parsed", spec.has("mcVersion") && spec.has("loader"));
        } catch (IOException | RuntimeException e) {
            checks.put("spec.parsed", false);
            failures.add("读取 spec 失败: " + e.getMessage());
        }

        LwjglSelfTest.run(checks, failures);

        int harvested = 0;
        if (spec != null && spec.has("gameJar")) {
            harvested = runFabricStage(spec, workDir, checks, failures);
        }

        boolean ok = checks.values().stream().allMatch(Boolean::booleanValue)
                && failures.size() == 0;

        JsonObject result = new JsonObject();
        result.addProperty("ok", ok);
        JsonObject checksJson = new JsonObject();
        checks.forEach(checksJson::addProperty);
        result.add("checks", checksJson);
        result.addProperty("harvested", harvested);
        result.add("failures", failures);
        result.addProperty("durationMillis", (System.nanoTime() - start) / 1_000_000);
        try {
            Files.writeString(workDir.resolve(RESULT_FILE), result.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("写 worker-result.json 失败: " + e.getMessage());
            System.exit(3);
        }
        System.exit(ok ? 0 : 1);
    }

    /**
     * fabric 阶段：Knot 引导 + 原版方块注册冒烟。返回注册的方块数（harvested 占位语义，
     * 待 BakedModel 采集接入后改为采集到的模型数）。
     */
    private static int runFabricStage(JsonObject spec, Path workDir,
                                      Map<String, Boolean> checks, JsonArray failures) {
        try {
            List<Path> modJars = new java.util.ArrayList<>();
            if (spec.has("modJars")) {
                spec.getAsJsonArray("modJars").forEach(m -> modJars.add(Path.of(m.getAsString())));
            }
            ClassLoader gameClassLoader = FabricKnotBootstrap.start(
                    Path.of(spec.get("gameJar").getAsString()), workDir, modJars);
            checks.put("fabric.knot.init", true);

            int blocks = MinecraftBlockSmoke.countRegisteredBlocks(gameClassLoader);
            checks.put("fabric.blocks.registered", blocks >= MinecraftBlockSmoke.MIN_EXPECTED_BLOCKS);
            if (blocks < MinecraftBlockSmoke.MIN_EXPECTED_BLOCKS) {
                failures.add("原版方块注册数异常: " + blocks + " < " + MinecraftBlockSmoke.MIN_EXPECTED_BLOCKS);
            }
            return blocks;
        } catch (Throwable t) {
            checks.putIfAbsent("fabric.knot.init", false);
            checks.putIfAbsent("fabric.blocks.registered", false);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            failures.add("fabric 引导失败: " + sw);
            return 0;
        }
    }

    private HarvestWorkerMain() {
    }
}
