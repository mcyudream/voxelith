package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.server.cli.RenderMapCli;
import online.yudream.voxelith.server.cli.RenderMapOptions;
import online.yudream.voxelith.server.support.McVersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

/**
 * 渲染任务服务：把「框选出来的范围」翻译成渲染管线参数并后台执行。
 *
 * <p>复用 CLI 的 {@link RenderMapCli#run(RenderMapOptions, PrintStream, PrintStream)}，
 * 所以网页触发与命令行触发走的是同一条管线、同一份产物布局——不另起一套实现，
 * 也就不会出现「网页渲染出来的和命令行不一样」。</p>
 *
 * <p>单线程执行：bake 会把窗口内全部区块网格留在内存里，并发跑两个任务只会把堆打爆。
 * 任务日志按行缓存（保留末尾若干行），前端用 {@code since} 增量拉取。</p>
 */
public class RenderJobService {

    private static final Logger log = LoggerFactory.getLogger(RenderJobService.class);

    /** 日志保留上限（行）：足够回看失败原因，又不至于把内存撑起来。 */
    private static final int MAX_LOG_LINES = 2000;

    /**
     * 分遍渲染时的拒绝线：内存已经不是瓶颈（只与单批相关），这条线挡的是「时间/磁盘离谱」的任务。
     * 青义整张 overworld 约 9.5 万区块、燕理约 8.8 万，12 万足够放下这两个量级。
     */
    private static final int MAX_BATCHED_CHUNKS = 120_000;

    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /**
     * @param logStart 已被裁掉的日志行数（日志只保留末尾若干行，前端按此校正增量游标）
     * @param logSize  当前日志总行数；前端用 {@code since=已拉到的行数} 增量取日志
     */
    public record RenderJob(
            String id,
            String uploadId,
            String mapId,
            String mapName,
            String dimension,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minY,
            State state,
            int progress,
            String stage,
            Instant startedAt,
            Instant finishedAt,
            Integer exitCode,
            int logStart,
            int logSize) {
    }

    /** 一个运行中/已结束的任务。 */
    private static final class Job {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        final List<String> logLines = new ArrayList<>();
        volatile int progress;
        volatile String stage = "排队中";
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile Integer exitCode;
        volatile WorldUpload upload;
        volatile RenderMapOptions options;
        private int dropped;

        synchronized void append(String line) {
            logLines.add(line);
            if (logLines.size() > MAX_LOG_LINES) {
                int excess = logLines.size() - MAX_LOG_LINES;
                logLines.subList(0, excess).clear();
                dropped += excess;
            }
        }

        synchronized List<String> linesSince(int since) {
            int from = Math.max(Math.max(0, since), dropped);
            from = Math.min(from, dropped + logLines.size());
            return List.copyOf(logLines.subList(from - dropped, logLines.size()));
        }

        synchronized int logSize() {
            return dropped + logLines.size();
        }

        synchronized int logStart() {
            return dropped;
        }
    }

    private final WorldStore store;
    private final RenderInputs inputs;
    private final Path publishDir;
    private final int defaultMinY;
    private final int defaultMaxLevel;
    private final boolean defaultLodAtlas;
    private final boolean defaultMeshopt;
    /** 单次渲染的非空区块上限；0 = 按渲染进程的堆自动估算（见 {@link #effectiveMaxChunks()}）。 */
    private final int configuredMaxChunks;
    /** 每批区块数；0 = 不分遍（单遍渲染，内存与窗口大小线性相关）。 */
    private final int configuredBatchChunks;
    private final long renderHeapBytes;
    private final RenderProcessLauncher launcher;
    /**
     * 任务表：已完成的任务只保留最近 {@link #RETAINED_FINISHED_JOBS} 个。
     * 每个任务带着最多 {@link #MAX_LOG_LINES} 行日志，长期运行必须设上界。
     */
    private final BoundedJobRegistry<Job> jobs = new BoundedJobRegistry<>(RETAINED_FINISHED_JOBS);

    /** 已完成任务的保留数量（运行中的不受限制，也不参与淘汰）。 */
    static final int RETAINED_FINISHED_JOBS = 50;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxelith-render-job");
        t.setDaemon(true);
        return t;
    });

    public RenderJobService(WorldStore store, RenderInputs inputs, Path publishDir,
                            int defaultMinY, int defaultMaxLevel, boolean defaultLodAtlas,
                            boolean defaultMeshopt,
                            int maxChunks, int batchChunks, long renderHeapBytes) {
        this.store = store;
        this.inputs = inputs;
        this.publishDir = publishDir.toAbsolutePath().normalize();
        this.defaultMinY = defaultMinY;
        this.defaultMaxLevel = defaultMaxLevel;
        this.defaultLodAtlas = defaultLodAtlas;
        this.defaultMeshopt = defaultMeshopt;
        this.configuredMaxChunks = Math.max(0, maxChunks);
        this.configuredBatchChunks = Math.max(0, batchChunks);
        this.renderHeapBytes = Math.max(0, renderHeapBytes);
        this.launcher = new RenderProcessLauncher(this.renderHeapBytes);
    }

    /**
     * 单次渲染的**建议**区块上限：显式配置优先，否则按渲染进程实际能用的堆估算。
     *
     * <p>换算按本机实测标定（青义校园 11,088 区块：峰值堆 6.6G、GC 后常驻 2.7G，约 0.5–0.6MB/区块；
     * 燕理那种密集建筑约 1.5MB/区块）。这里取 1MB/区块、几何只吃堆的一半，其余留给模型库
     * （models.json.gz 解开的四元组，GB 量级）与 GC 余量。</p>
     *
     * <p>这是**建议值**（前端据此提示），不是拒绝线——见 {@link #hardMaxChunks()}。</p>
     */
    int effectiveMaxChunks() {
        if (configuredMaxChunks > 0) {
            return configuredMaxChunks;
        }
        // 独立进程跑 = 用它的堆；退回进程内跑 = 只能吃 web 进程的堆
        long heap = launcher.canSpawn() ? renderHeapBytes : Runtime.getRuntime().maxMemory();
        if (heap <= 0) {
            return 0;
        }
        return (int) Math.max(256, heap / 2 / (1024 * 1024));
    }

    /**
     * 拒绝线（交给管线的 maxChunks）。
     *
     * <p>为什么比建议值宽 3 倍：建议值是按「典型密度」估的，而实际密度能差 3 倍以上
     * （实测：青义校园 0.5MB/区块，燕理密集建筑 1.5MB/区块）。渲染现在跑在独立子进程里，
     * 超了也只是这个任务失败并明确报「内存不足」，不会再拖死服务进程——所以宁可先让它试，
     * 也不该把「能跑完的范围」提前拒掉。真正的荒谬范围（整张 overworld 那种）仍然会被挡下。</p>
     */
    int hardMaxChunks() {
        if (configuredMaxChunks > 0) {
            return configuredMaxChunks;
        }
        int recommended = effectiveMaxChunks();
        if (recommended <= 0) {
            return 0;
        }
        // 分遍渲染时内存只与单批相关，拒绝线改成「时间/磁盘」保护
        return configuredBatchChunks > 0
                ? Math.max(recommended * 3, MAX_BATCHED_CHUNKS)
                : recommended * 3;
    }

    /** 每批区块数；0 = 不分遍。前端据此说明「大范围会自动分遍」。 */
    int batchChunks() {
        return configuredBatchChunks;
    }

    /**
     * 删除已发布地图：发布目录（瓦片/清单/全景）与渲染工作目录一并清掉。
     *
     * <p>mapId 做路径校验，防目录穿越；正在运行的渲染任务不受影响
     * （它们完成时会重建同名目录，属可接受行为）。</p>
     *
     * @return 是否有目录被删除
     */
    public boolean deletePublished(String mapId) {
        if (mapId == null || mapId.isBlank() || mapId.contains("/") || mapId.contains("\\")
                || mapId.contains("..")) {
            throw new IllegalArgumentException("非法 mapId: " + mapId);
        }
        boolean removed = false;
        for (Path dir : List.of(publishDir.resolve(mapId), inputs.workDir().resolve(mapId))) {
            if (!Files.exists(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("删除失败: {}", p, e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("删除目录失败: " + dir, e);
            }
            removed = true;
        }
        return removed;
    }

    public List<RenderJob> list() {
        return jobs.list().stream()
                .map(this::snapshot)
                .sorted(Comparator.comparing(RenderJob::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Optional<RenderJob> find(String id) {
        return jobs.get(id).map(this::snapshot);
    }

    public List<String> logSince(String id, int since) {
        return jobs.get(id).map(job -> job.linesSince(since)).orElseGet(List::of);
    }

    /**
     * 提交渲染任务。
     *
     * @throws IllegalArgumentException 参数不合法（存档/维度/范围/资源包）
     */
    public RenderJob submit(RenderRequest request) {
        WorldUpload upload = store.find(request.uploadId())
                .orElseThrow(() -> new IllegalArgumentException("存档不存在: " + request.uploadId()));
        String dimension = request.dimension() == null || request.dimension().isBlank()
                ? "minecraft:overworld" : request.dimension();
        if (!upload.dimensions().contains(dimension)) {
            throw new IllegalArgumentException("该存档没有维度: " + dimension);
        }
        String mapId = request.mapId() == null || request.mapId().isBlank()
                ? upload.id() : request.mapId().trim();
        if (!WorldNames.isSafeId(mapId)) {
            throw new IllegalArgumentException(
                    "地图 id 不能为空、不能超过 48 个字符，也不能包含 <>:\"/\\|?* 等字符: " + mapId);
        }

        List<Path> packs = resolvePacks(request, upload.versionName());
        if (packs.isEmpty()) {
            throw new IllegalArgumentException(
                    "找不到可用的资源包（原版 client jar）。请在高级设置里指定，"
                            + "或把 client-*.jar 放到仓库的 .cache/minecraft 下");
        }
        Path modelsFile = request.modelsFile() == null || request.modelsFile().isBlank()
                ? inputs.modelsFile().orElse(null)
                : Path.of(request.modelsFile().trim());
        if (modelsFile != null && !Files.isRegularFile(modelsFile)) {
            throw new IllegalArgumentException("采集产物不存在: " + modelsFile);
        }

        int minX = requireRange(request.minX(), request.maxX(), "X");
        int maxX = request.maxX();
        int minZ = requireRange(request.minZ(), request.maxZ(), "Z");
        int maxZ = request.maxZ();

        // 采集版本不写死：优先资源包文件名里的版本，其次存档版本，最后才用配置默认值。
        // 三者不一致会让 models.json.gz 的模型与包里的贴图对不上（大片品红 / 空洞）
        String packName = packs.isEmpty() ? null : packs.getFirst().getFileName().toString();
        String mcVersion = McVersionResolver.resolve(
                null, packName, upload.versionName(), inputs.mcVersion());
        RenderMapOptions options = new RenderMapOptions(
                Path.of(upload.worldDir()),
                dimension,
                mapId,
                request.mapName() == null || request.mapName().isBlank()
                        ? upload.name() : request.mapName().trim(),
                packs,
                // 每个地图独立 work 子目录：heightfield.bin / 图集缓存等中间产物
                // 按地图隔离，否则不同存档的渲染会互相串数据
                inputs.workDir().resolve(mapId),
                publishDir,
                modelsFile,
                null, null, null, null,
                request.maxLevel() == null ? defaultMaxLevel : request.maxLevel(),
                0,
                request.lodAtlas() == null ? defaultLodAtlas : request.lodAtlas(),
                request.minY() == null ? defaultMinY : request.minY(),
                minX, maxX, minZ, maxZ,
                mcVersion,
                inputs.loaderVersion(),
                // 采集需要 worker 子进程 classpath（配置或自动发现）；拿得到就在进程内跑一次
                // headless 采集（uvlock/元素旋转等按游戏几何定义正确），拿不到才退回静态解析
                inputs.workerClasspath().isEmpty(),
                inputs.workerClasspath(),
                hardMaxChunks(),
                configuredBatchChunks,
                request.meshopt() == null ? defaultMeshopt : request.meshopt());

        Job job = new Job();
        job.upload = upload;
        job.options = options;
        job.startedAt = Instant.now();
        job.stage = "排队中";
        job.append("提交渲染任务：" + upload.name() + " / " + dimension);
        job.append("范围 X[" + minX + "," + maxX + "] Z[" + minZ + "," + maxZ + "]"
                + (request.minY() == null && defaultMinY == BakeCommand.NO_MIN_Y
                ? "" : " 最低高度 " + options.minY()));
        int suggested = effectiveMaxChunks();
        int hard = hardMaxChunks();
        if (suggested > 0) {
            job.append("建议单次不超过 " + suggested + " 个非空区块（实测约 0.5–1.5MB/区块）；"
                    + "硬上限 " + hard + "，超过直接拒绝");
        }
        if (configuredBatchChunks > 0) {
            job.append("超过建议值时自动分遍渲染：每批约 " + configuredBatchChunks
                    + " 区块（按 region 对齐），峰值内存只与单批相关（代价是时间与磁盘）");
        }
        String packNote = packNote(upload.versionName(), packs);
        if (packNote != null) {
            job.append(packNote);
        }
        jobs.put(job.id, job);
        pool.submit(() -> execute(job));
        return snapshot(job);
    }

    /**
     * 可选资源包：请求里给了就用请求的，否则按存档版本自动挑（见
     * {@link RenderInputs#packsForVersion(String)}）。
     */
    private List<Path> resolvePacks(RenderRequest request, String worldVersion) {
        if (request.packs() != null && !request.packs().isEmpty()) {
            List<Path> packs = new ArrayList<>();
            for (String raw : request.packs()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Path path = Path.of(raw.trim());
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("资源包不存在: " + path);
                }
                packs.add(path);
            }
            if (!packs.isEmpty()) {
                return packs;
            }
        }
        return inputs.packsForVersion(worldVersion);
    }

    /**
     * 资源包与存档版本是否对得上——对不上就直接说清楚，否则用户只会看到一片品红。
     *
     * @return 要写进任务日志的一行提示；没有问题（或无法判断）时返回 null
     */
    private static String packNote(String worldVersion, List<Path> packs) {
        if (worldVersion == null || worldVersion.isBlank() || packs.isEmpty()) {
            return null;
        }
        String version = worldVersion.trim();
        for (Path pack : packs) {
            String name = pack.getFileName().toString();
            if (name.contains(version)) {
                return "资源包按存档版本 " + version + " 匹配：" + name;
            }
        }
        // 名字里带版本号的包才对得上；名字不带版本号的（自定义整合包）不妄下结论
        boolean anyVersioned = packs.stream()
                .anyMatch(p -> p.getFileName().toString().matches(".*\\d+\\.\\d+.*"));
        if (!anyVersioned) {
            return null;
        }
        return "注意：没有找到与存档版本 " + version + " 匹配的资源包，当前用的是 "
                + packs.getFirst().getFileName() + "——版本不一致时缺失的贴图会渲染成品红色；"
                + "把 client-" + version + ".jar 放到仓库 .cache/minecraft/ 下即可自动匹配";
    }

    private static int requireRange(Integer min, Integer max, String axis) {
        if (min == null || max == null) {
            throw new IllegalArgumentException("请先在二维地图上框选渲染范围（缺少 " + axis + " 范围）");
        }
        if (min > max) {
            throw new IllegalArgumentException(axis + " 范围起点大于终点: " + min + " > " + max);
        }
        return min;
    }

    private void execute(Job job) {
        try {
            job.state.set(State.RUNNING);
            job.stage = "启动管线";
            job.append("开始渲染…");
            int code = runPipeline(job);
            job.exitCode = code;
            job.finishedAt = Instant.now();
            if (code == 0) {
                job.state.set(State.SUCCEEDED);
                job.progress = 100;
                job.stage = "已完成";
            } else {
                job.state.set(State.FAILED);
                job.stage = switch (code) {
                    case 3 -> "失败（范围超过单次渲染上限）";
                    case 4 -> "失败（内存不足）";
                    default -> "失败（退出码 " + code + "）";
                };
            }
        } finally {
            // 完成后进入淘汰队列：任务表只保留最近若干个，避免长期运行内存只增不减。
            // 放 finally 里是为了异常路径也照样入队，不会留下永远「运行中」的记录。
            jobs.markFinished(job.id);
        }
    }

    /**
     * 跑一次渲染管线，返回退出码。
     *
     * <p>优先起**独立 JVM 子进程**：渲染是内存大户（bake 把窗口内全部区块网格留在堆里），
     * 在 web 进程内跑，一次超范围渲染就能把服务本身拖死——前端看到的就是 {@code Failed to fetch}，
     * 而任务日志停在半路。独立进程还顺带解决了「web 进程的堆是给 Tomcat 用的，渲染没法单独调大」：
     * 子进程按 {@code render.heap} 单独要堆，崩了也只是这个任务失败，地图服务和已发布的地图都不受影响。</p>
     *
     * <p>打包成 fat jar 时 {@code java.class.path} 只有那个 jar（依赖在嵌套目录里），
     * 独立进程拉不起来，于是退回进程内执行——宁可少一层隔离，也不能让功能不可用。</p>
     */
    private int runPipeline(Job job) {
        if (launcher.canSpawn()) {
            Integer code = runViaSubprocess(job);
            if (code != null) {
                return code;
            }
        }
        return runInProcess(job);
    }

    /** 渲染子进程的堆上限（日志用）。 */
    private String heapLabel() {
        if (renderHeapBytes <= 0) {
            return "JVM 默认";
        }
        return (renderHeapBytes / (1024L * 1024 * 1024)) + "G";
    }

    /**
     * 进程内跑管线（子进程方式不可用，或子进程起不来时的兜底）。
     *
     * @return 退出码
     */
    private int runInProcess(Job job) {
        // 管线按行输出（进度/阶段/告警），逐行解析成阶段进度；同时原样进任务日志
        LineSink sink = new LineSink(job);
        PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = RenderMapCli.run(job.options, stream, stream);
        } catch (Throwable e) {
            log.error("渲染任务异常: {}", job.id, e);
            job.append(e instanceof OutOfMemoryError
                    ? "内存不足：窗口内区块的网格放不进服务进程的堆（请缩小范围）"
                    : "渲染异常: " + e);
            code = e instanceof OutOfMemoryError ? 4 : 1;
        } finally {
            stream.flush();
            sink.end();
        }
        return code;
    }

    /**
     * 子进程方式跑管线：输出按行回传进任务日志，阶段与进度照旧推进。
     *
     * @return 退出码；子进程起不来时返回 null，让调用方退回进程内执行
     */
    private Integer runViaSubprocess(Job job) {
        job.append("渲染在独立进程里执行（堆上限 " + heapLabel() + "）");
        try {
            int code = launcher.run(job.options, line -> acceptLine(job, line));
            reportPeakHeap(job);
            return code;
        } catch (IOException e) {
            log.warn("渲染子进程起不来，退回服务进程内执行: {}", job.id, e);
            job.append("独立进程启动失败，改在服务进程内渲染：" + e.getMessage());
            return null;
        }
    }

    /**
     * 渲染完报一次实际内存占用（读子进程的 GC 日志）。
     *
     * <p>这一行的意义在于：上限是估出来的，而这一行是测出来的。用户看到
     * 「峰值 3.4G / 上限 16G」就知道还能把范围开大几倍，看到「14.8G / 16G」就知道
     * 该调 {@code render.heap} 而不是硬试。</p>
     */
    private void reportPeakHeap(Job job) {
        long[] peak = RenderProcessLauncher.peakHeap(RenderProcessLauncher.gcLog(job.options));
        if (peak == null) {
            return;
        }
        job.append("本次渲染峰值堆 " + humanBytes(peak[0])
                + (renderHeapBytes > 0 ? " / 上限 " + humanBytes(renderHeapBytes) : "")
                + "（GC 后常驻 " + humanBytes(peak[1]) + "）");
        if (renderHeapBytes > 0 && peak[0] * 100 / renderHeapBytes >= 80) {
            job.append("  内存已经吃紧：再放大范围前先调大 render.heap（或缩小范围）");
        }
    }

    private static String humanBytes(long bytes) {
        double gib = bytes / (1024.0 * 1024 * 1024);
        if (gib >= 1) {
            return String.format(java.util.Locale.ROOT, "%.1fG", gib);
        }
        return String.format(java.util.Locale.ROOT, "%.0fM", bytes / (1024.0 * 1024));
    }

    /**
     * 逐行落日志并推进阶段：管线打印的关键行就是进度的事实来源。
     *
     * <p>只在换行符处成行——{@code PrintStream} 会在每次 {@code format} 片段后 flush，
     * 若把 flush 也当断行，一行 {@code printf} 会被拆成「扫描区块 / 10240 / / / 135168 …」好几行。
     * 结尾没换行的残行由 {@link #end()} 收尾。</p>
     */
    private static final class LineSink extends ByteArrayOutputStream {

        private final Job job;

        LineSink(Job job) {
            this.job = job;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emitLine();
                return;
            }
            if (b != '\r') {
                super.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() {
            // 刻意不输出残行：见类注释
        }

        /** 管线跑完后的收尾：把最后一行没有换行符的输出也落盘。 */
        synchronized void end() {
            emitLine();
        }

        private void emitLine() {
            if (size() == 0) {
                return;
            }
            String line = toString(StandardCharsets.UTF_8);
            reset();
            acceptLine(job, line);
        }
    }

    /**
     * 收一行管线输出：落进任务日志，并据关键行推进阶段与进度。
     *
     * <p>进程内（{@link LineSink}）与独立子进程（{@link RenderProcessLauncher}）两条路径
     * 都走这里，保证「网页看到的进度」与跑法无关。</p>
     */
    private static void acceptLine(Job job, String rawLine) {
        String line = rawLine == null ? "" : rawLine.stripTrailing();
        if (line.isEmpty()) {
            return;
        }
        job.append(line);
        stageOf(line).ifPresent(stage -> {
            job.stage = stage.name();
            job.progress = stage.progress();
        });
    }

    /** 管线关键行 → 阶段进度。 */
    private record Stage(String name, int progress) {
    }

    private static Optional<Stage> stageOf(String line) {
        // 多遍渲染：批次进度按「第 i/N 批」折算，整体落在 15%~95% 区间
        Matcher batch = BATCH_LINE.matcher(line);
        if (batch.find()) {
            int index = Integer.parseInt(batch.group(1));
            int total = Math.max(1, Integer.parseInt(batch.group(2)));
            return Optional.of(new Stage("多遍渲染 " + index + "/" + total + " 批",
                    15 + Math.min(79, 79 * (index - 1) / total)));
        }
        if (line.contains("共享图集已就绪")) {
            return Optional.of(new Stage("准备共享图集", 14));
        }
        if (line.contains("多遍渲染：窗口")) {
            return Optional.of(new Stage("规划批次", 12));
        }
        if (line.contains("=== 渲染完成 ===")) {
            return Optional.of(new Stage("发布清单", 100));
        }
        if (line.contains("lod 完成")) {
            return Optional.of(new Stage("生成 LOD 金字塔", 92));
        }
        if (line.contains("tile 完成")) {
            return Optional.of(new Stage("生成瓦片", 78));
        }
        if (line.contains("bake 完成")) {
            return Optional.of(new Stage("烘焙几何", 60));
        }
        if (line.contains("非空区块")) {
            return Optional.of(new Stage("烘焙几何", 15));
        }
        if (line.contains("模型来源")) {
            return Optional.of(new Stage("准备模型几何", 10));
        }
        if (line.contains("先采集模型几何")) {
            return Optional.of(new Stage("采集模型几何", 3));
        }
        if (line.contains("扫描区块")) {
            return Optional.of(new Stage("扫描区块", 8));
        }
        return Optional.empty();
    }

    /** 「多遍渲染 批次 3/47：…」→ 取 3/47。 */
    private static final java.util.regex.Pattern BATCH_LINE =
            java.util.regex.Pattern.compile("多遍渲染 批次 (\\d+)/(\\d+)");

    private RenderJob snapshot(Job job) {
        WorldUpload upload = job.upload;
        RenderMapOptions options = job.options;
        return new RenderJob(
                job.id,
                upload == null ? "" : upload.id(),
                options == null ? "" : options.mapId(),
                options == null ? "" : options.mapName(),
                options == null ? "" : options.dimension(),
                options == null || options.minX() == null ? 0 : options.minX(),
                options == null || options.maxX() == null ? 0 : options.maxX(),
                options == null || options.minZ() == null ? 0 : options.minZ(),
                options == null || options.maxZ() == null ? 0 : options.maxZ(),
                options == null ? 0 : options.minY(),
                job.state.get(),
                job.progress,
                job.stage,
                job.startedAt,
                job.finishedAt,
                job.exitCode,
                job.logStart(),
                job.logSize());
    }
}
