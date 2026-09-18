package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.server.cli.RenderMapCli;
import online.yudream.voxelith.server.cli.RenderMapOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 把一次渲染交给**独立 JVM 子进程**跑。
 *
 * <p>为什么不在 web 进程里直接跑：bake 会把窗口内全部区块的网格留在堆里，一次超范围的渲染
 * （整图几万区块）足以把服务进程拖到内存耗尽——前端此时看到的是 {@code Failed to fetch}，
 * 任务日志停在半路，地图列表也刷不出来。渲染本来就是内存大户（CLI 一直要求 {@code -Pheap=8g}），
 * 而 web 进程的堆要给 Tomcat 用、没法为单次渲染单独调大。拆成子进程后：</p>
 * <ul>
 *   <li>渲染崩了（内存不足 / JVM 退出）只是这一个任务失败，地图服务与已发布地图不受影响；</li>
 *   <li>渲染可以按 {@code render.heap} 单独要堆，不受 web 进程堆的牵连；</li>
 *   <li>日志按行回传，进度照旧。</li>
 * </ul>
 *
 * <p>子进程用「当前 JVM 的 java + 当前 classpath + {@link RenderMapCli}}」启动，
 * 因此开发态（bootRun / IDE）与 CLI 跑的是同一份代码同一套依赖。打包成 fat jar 时
 * 依赖在嵌套 jar 里、普通 {@code -cp} 拉不起来，{@link #canSpawn()} 会返回 false，
 * 调用方退回进程内执行——少一层隔离，但功能不会不可用。</p>
 */
final class RenderProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(RenderProcessLauncher.class);

    private final long heapBytes;

    /**
     * @param heapBytes 渲染子进程的堆上限；0 = 不传 -Xmx（用 JVM 默认）
     */
    RenderProcessLauncher(long heapBytes) {
        this.heapBytes = Math.max(0, heapBytes);
    }

    /** 子进程方式是否可用。 */
    boolean canSpawn() {
        URL self = RenderMapCli.class.getResource("RenderMapCli.class");
        if (self == null || !"file".equals(self.getProtocol())) {
            log.info("渲染将跑在服务进程内：当前部署形态（fat jar / 嵌套 jar）不支持独立进程");
            return false;
        }
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank() || !Files.isRegularFile(javaExecutable())) {
            log.info("渲染将跑在服务进程内：java.class.path 或 java 可执行文件不可用");
            return false;
        }
        return true;
    }

    /**
     * 启动子进程跑完一次渲染（阻塞直到退出）。
     *
     * @param onLine 渲染输出按行回调（stdout/stderr 已合并，保持时序）
     * @return 进程退出码
     * @throws IOException 子进程起不来（调用方应退回进程内执行）
     */
    int run(RenderMapOptions options, Consumer<String> onLine) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(options));
        // 合并 stderr：管线既往 out 也往 err 打进度与告警，合并后时序就是用户看到的顺序
        builder.redirectErrorStream(true);
        builder.directory(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().toFile());
        // GC 日志要写到工作目录里，目录得先存在（否则 JVM 打不开文件、日志会窜到 stdout）
        Files.createDirectories(options.workDir());
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onLine.accept(line);
            }
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("渲染子进程被中断", e);
        }
    }

    /** 子进程完整命令行（拆分出来便于单测断言参数拼装）。 */
    static List<String> command(RenderMapOptions options, long heapBytes, String classpath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable().toString());
        // 中文日志按 UTF-8 输出，否则 Windows 默认 GBK 会让回传的日志变成乱码
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        // 记 GC 日志：渲染完用它报「实际峰值堆」，用户据此判断还能不能把范围开大
        cmd.add("-Xlog:gc:file=" + gcLog(options) + ":time,uptime,level,tags");
        if (heapBytes > 0) {
            cmd.add("-Xmx" + Math.max(256, heapBytes / (1024 * 1024)) + "m");
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(RenderMapCli.class.getName());
        cmd.addAll(options.toArgs());
        return cmd;
    }

    private List<String> command(RenderMapOptions options) {
        return command(options, heapBytes, System.getProperty("java.class.path", ""));
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    /** 本次渲染的 GC 日志路径（放在该地图的工作目录里，跟其它中间产物一起）。 */
    static Path gcLog(RenderMapOptions options) {
        return options.workDir().toAbsolutePath().normalize().resolve("render-gc.log");
    }

    /**
     * 从 GC 日志里读出「峰值堆」与「GC 后常驻」。
     *
     * <p>G1 的行形如 {@code GC(42) Pause Young ... 2048M->512M(16384M) 12ms}：
     * 箭头左边是所有 GC 里「最满」的那个数（≈ 峰值足迹），右边是回收后仍然活着的量。
     * 两个数分别回答「还能不能再开大」和「几何本身占了多少」。</p>
     *
     * @return {@code [峰值字节, 常驻字节]}；读不到则返回 null
     */
    static long[] peakHeap(Path gcLog) {
        if (!Files.isRegularFile(gcLog)) {
            return null;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(\\d+)([KMG])->(\\d+)([KMG])\\((\\d+)([KMG])\\)");
        long peak = -1;
        long live = -1;
        try {
            for (String line : Files.readAllLines(gcLog, StandardCharsets.UTF_8)) {
                var matcher = pattern.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                peak = Math.max(peak, bytes(matcher.group(1), matcher.group(2)));
                live = Math.max(live, bytes(matcher.group(3), matcher.group(4)));
            }
        } catch (IOException e) {
            log.warn("读取 GC 日志失败: {}", gcLog, e);
            return null;
        }
        return peak < 0 ? null : new long[]{peak, live};
    }

    private static long bytes(String value, String unit) {
        long n = Long.parseLong(value);
        return switch (unit) {
            case "K" -> n * 1024L;
            case "M" -> n * 1024L * 1024;
            default -> n * 1024L * 1024 * 1024;
        };
    }
}
