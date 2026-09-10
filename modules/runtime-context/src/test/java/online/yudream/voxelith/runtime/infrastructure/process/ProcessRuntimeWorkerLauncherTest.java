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
