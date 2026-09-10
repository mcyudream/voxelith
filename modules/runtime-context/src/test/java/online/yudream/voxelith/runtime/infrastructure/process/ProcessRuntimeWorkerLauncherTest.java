package online.yudream.voxelith.runtime.infrastructure.process;

import online.yudream.voxelith.runtime.application.HarvestModelsUseCase;
import online.yudream.voxelith.runtime.domain.HarvestReport;
import online.yudream.voxelith.runtime.domain.LoaderKind;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程隔离端到端：真实拉起 worker 子 JVM（无 native 依赖），
 * 验证 LWJGL stub 在干净 JVM 中的链接可行性 + 文件协议往返。
 */
class ProcessRuntimeWorkerLauncherTest {

    @TempDir
    Path workDir;

    @Test
    void workerSelfTestPassesInIsolatedSubprocess() {
        ProcessRuntimeWorkerLauncher launcher =
                new ProcessRuntimeWorkerLauncher(workerClasspath(), Duration.ofMinutes(2));
        HarvestReport report = new HarvestModelsUseCase(launcher).harvest(
                new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.9", List.of(), workDir));

        assertThat(report.failures()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.checks())
                .containsEntry("spec.parsed", true)
                .containsEntry("lwjgl.glfw.init", true)
                .containsEntry("lwjgl.glfw.window", true)
                .containsEntry("lwjgl.gl.capabilities", true)
                .containsEntry("lwjgl.gl11.query", true)
                .containsEntry("lwjgl.gl30.vao", true);
        // 协议落盘文件齐备
        assertThat(workDir.resolve(WorkerProtocol.SPEC_FILE)).exists();
        assertThat(workDir.resolve(WorkerProtocol.RESULT_FILE)).exists();
        assertThat(workDir.resolve("worker.log")).exists();
    }

    @Test
    void missingWorkerMainYieldsFailureReportNotCrash() {
        // classpath 只有 gson 没有 worker 类 → 子进程 main 类找不到
        ProcessRuntimeWorkerLauncher launcher = new ProcessRuntimeWorkerLauncher(
                List.of(gsonJar()), Duration.ofMinutes(1));
        HarvestReport report = launcher.launch(
                new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.9", List.of(), workDir));

        assertThat(report.ok()).isFalse();
        assertThat(report.failures()).isNotEmpty();
    }

    /**
     * 真实 headless 引导 + 模型采集：provision MC 1.20.1 + fabric-loader 0.16.14，
     * 子进程内 Knot remap 游戏 jar 后强制初始化 Blocks，驱动原版 ModelLoader 全量烘焙
     * 并导出 models.json.gz（gzip NDJSON）。首次运行需 remap（约 30s 级），
     * 之后走 .fabric/remappedJars 缓存。
     */
    @Test
    void fabricBootstrapRegistersVanillaBlocks() throws Exception {
        ProcessRuntimeWorkerLauncher launcher = new ProcessRuntimeWorkerLauncher(
                workerClasspath(),
                new online.yudream.voxelith.runtime.infrastructure.provision.HttpRuntimeProvisioner(),
                Path.of("build/runtime-cache"),
                Duration.ofMinutes(10));
        HarvestReport report = new HarvestModelsUseCase(launcher).harvest(
                new RuntimeSpec("1.20.1", LoaderKind.FABRIC, "0.16.14", List.of(), workDir));

        String log = Files.isRegularFile(workDir.resolve("worker.log"))
                ? Files.readString(workDir.resolve("worker.log")) : "<无 worker.log>";
        assertThat(report.ok()).as("failures=%s\n--- worker.log ---\n%s", report.failures(), log).isTrue();
        assertThat(report.checks())
                .containsEntry("fabric.knot.init", true)
                .containsEntry("fabric.blocks.registered", true)
                .containsEntry("fabric.models.baked", true);
        assertThat(report.harvested()).isGreaterThan(900);

        // 模型采集产物：1.20.1 原版约 2.4 万状态 / 29.5 万 quad
        assertThat(report.modelsFile()).isEqualTo("models.json.gz");
        assertThat(report.statesExported()).isGreaterThan(20_000);
        assertThat(report.quadsExported()).isGreaterThan(100_000);

        Path modelsFile = workDir.resolve(report.modelsFile());
        assertThat(modelsFile).exists();
        spotCheckExportedModels(modelsFile);
    }

    /** 解压 models.json.gz 抽查：meta 头合法，且石头方块的 quad 带正确贴图与 0~16 坐标。 */
    private static void spotCheckExportedModels(Path modelsFile) throws Exception {
        List<String> lines;
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(modelsFile));
             var reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }
        assertThat(lines).isNotEmpty();
        com.google.gson.JsonObject meta = com.google.gson.JsonParser
                .parseString(lines.get(0)).getAsJsonObject();
        assertThat(meta.get("format").getAsString()).isEqualTo("voxelith-models/1");
        assertThat(meta.get("sprites").getAsInt()).isGreaterThan(1_000);

        com.google.gson.JsonObject stone = lines.stream().skip(1)
                .map(l -> com.google.gson.JsonParser.parseString(l).getAsJsonObject())
                .filter(o -> "minecraft:stone".equals(o.get("block").getAsString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("导出中找不到 minecraft:stone"));
        com.google.gson.JsonArray states = stone.getAsJsonArray("states");
        assertThat(states).isNotEmpty();
        com.google.gson.JsonArray quads = states.get(0).getAsJsonObject().getAsJsonArray("quads");
        assertThat(quads.size()).isGreaterThanOrEqualTo(6);
        for (var q : quads) {
            com.google.gson.JsonObject quad = q.getAsJsonObject();
            assertThat(quad.get("tex").getAsString()).isEqualTo("minecraft:block/stone");
            for (var p : quad.getAsJsonArray("pos")) {
                assertThat(p.getAsFloat()).isBetween(0.0f, 16.0f);
            }
            for (var u : quad.getAsJsonArray("uv")) {
                assertThat(u.getAsFloat()).isBetween(0.0f, 16.0f);
            }
        }
    }

    /** worker 产物 classes 目录 + gson jar（与本模块测试运行时同源）。 */
    private static List<Path> workerClasspath() {
        Path workerClasses = Path.of(System.getProperty("user.dir"))
                .resolve("../runtime-worker/build/classes/java/main").normalize();
        assertThat(workerClasses).as("runtime-worker classes 目录").isDirectory();
        assertThat(workerClasses.resolve("online/yudream/voxelith/runtime/worker/HarvestWorkerMain.class"))
                .exists();
        List<Path> cp = new ArrayList<>(List.of(workerClasses));
        cp.add(gsonJar());
        return cp;
    }

    private static Path gsonJar() {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains("gson") && entry.endsWith(".jar")) {
                return Path.of(entry);
            }
        }
        throw new IllegalStateException("测试 classpath 中找不到 gson jar");
    }
}
