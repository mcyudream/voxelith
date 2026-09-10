package online.yudream.voxelith.runtime.infrastructure.process;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.runtime.domain.HarvestReport;
import online.yudream.voxelith.runtime.domain.RuntimeSpec;
import online.yudream.voxelith.runtime.domain.RuntimeWorkerLauncher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程隔离的 worker 启动器：以独立 JVM 子进程运行 runtime-worker，
 * 通过 workDir 下的 spec.json / worker-result.json 交换数据（ADR 0001）。
 * worker 崩溃/超时不影响主进程，报告缺失时返回失败报告而非抛异常。
 */
public final class ProcessRuntimeWorkerLauncher implements RuntimeWorkerLauncher {

    public static final String WORKER_MAIN_CLASS = "online.yudream.voxelith.runtime.worker.HarvestWorkerMain";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final List<Path> workerClasspath;
    private final Duration timeout;

    /**
     * @param workerClasspath worker 子 JVM 的完整 classpath（runtime-worker 产物 + 其依赖）
     */
    public ProcessRuntimeWorkerLauncher(List<Path> workerClasspath) {
        this(workerClasspath, DEFAULT_TIMEOUT);
    }

    public ProcessRuntimeWorkerLauncher(List<Path> workerClasspath, Duration timeout) {
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("worker classpath 不能为空");
        }
        this.workerClasspath = List.copyOf(workerClasspath);
        this.timeout = timeout;
    }

    @Override
    public HarvestReport launch(RuntimeSpec spec) {
        try {
            Files.createDirectories(spec.workDir());
            writeSpec(spec);

            List<String> command = new ArrayList<>();
            command.add(javaExecutable().toString());
            command.add("-cp");
            command.add(classpathString());
            command.add(WORKER_MAIN_CLASS);
            command.add("--spec");
            command.add(spec.workDir().resolve(WorkerProtocol.SPEC_FILE).toString());

            Process process = new ProcessBuilder(command)
                    .directory(spec.workDir().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(spec.workDir().resolve("worker.log").toFile())
                    .start();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return HarvestReport.failed(List.of("worker 超时（" + timeout + "），已强制终止"), timeout.toMillis());
            }
            Path resultFile = spec.workDir().resolve(WorkerProtocol.RESULT_FILE);
            if (process.exitValue() != 0 || !Files.isRegularFile(resultFile)) {
                return HarvestReport.failed(List.of(
                        "worker 退出码 " + process.exitValue() + " 且报告文件缺失，详见 worker.log"), 0);
            }
            return readReport(resultFile);
        } catch (IOException e) {
            throw new UncheckedIOException("启动 worker 子进程失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 worker 被中断", e);
        }
    }

    private void writeSpec(RuntimeSpec spec) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("mcVersion", spec.mcVersion());
        json.addProperty("loader", spec.loader().name());
        json.addProperty("loaderVersion", spec.loaderVersion());
        var mods = new com.google.gson.JsonArray();
        spec.modJars().forEach(p -> mods.add(p.toAbsolutePath().toString()));
        json.add("modJars", mods);
        Files.writeString(spec.workDir().resolve(WorkerProtocol.SPEC_FILE),
                json.toString(), StandardCharsets.UTF_8);
    }

    private HarvestReport readReport(Path resultFile) throws IOException {
        JsonObject json = JsonParser.parseString(
                Files.readString(resultFile, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        if (json.has(WorkerProtocol.FIELD_CHECKS)) {
            json.getAsJsonObject(WorkerProtocol.FIELD_CHECKS)
                    .entrySet().forEach(e -> checks.put(e.getKey(), e.getValue().getAsBoolean()));
        }
        List<String> failures = new ArrayList<>();
        if (json.has(WorkerProtocol.FIELD_FAILURES)) {
            json.getAsJsonArray(WorkerProtocol.FIELD_FAILURES)
                    .forEach(f -> failures.add(f.getAsString()));
        }
        return new HarvestReport(
                json.get(WorkerProtocol.FIELD_OK).getAsBoolean(),
                checks,
                json.has(WorkerProtocol.FIELD_HARVESTED)
                        ? json.get(WorkerProtocol.FIELD_HARVESTED).getAsInt() : 0,
                failures,
                json.has(WorkerProtocol.FIELD_DURATION)
                        ? json.get(WorkerProtocol.FIELD_DURATION).getAsLong() : 0);
    }

    private String classpathString() {
        StringBuilder sb = new StringBuilder();
        for (Path p : workerClasspath) {
            if (sb.length() > 0) {
                sb.append(java.io.File.pathSeparatorChar);
            }
            sb.append(p.toAbsolutePath());
        }
        return sb.toString();
    }

    static Path javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home")).resolve("bin");
        Path exe = bin.resolve(isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(exe) ? exe : bin.resolve("java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
