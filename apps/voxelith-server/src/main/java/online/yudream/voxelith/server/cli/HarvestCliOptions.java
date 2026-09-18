package online.yudream.voxelith.server.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * headless 采集入口（{@code harvestModels}）的命令行参数。纯解析、不碰文件系统之外的状态，
 * 便于单测；{@link HarvestModelsCli} 只负责按解析结果装配用例并执行。
 *
 * @param mcVersion        目标 MC 版本
 * @param loaderVersion    Fabric loader 版本
 * @param workDir          产物与报告目录（models.json.gz / model-acquisition.json 落此处）
 * @param assetJar         兜底静态解析用的资源 jar（通常是原版 client jar）
 * @param modJars          额外加载的 mod jar（其方块会被采集进 models.json.gz）
 * @param workerClasspath  worker 子 JVM 的基础 classpath 项（由 Gradle 任务注入）
 * @param provisionCacheDir MC/Fabric 依赖下载缓存目录
 * @param timeoutMinutes   worker 超时分钟数
 * @param skipProvision    跳过下载与 Knot 引导，仅跑 LWJGL 自检（排障用）
 * @param help             是否只打印帮助
 */
public record HarvestCliOptions(
        String mcVersion,
        String loaderVersion,
        Path workDir,
        Path assetJar,
        List<Path> modJars,
        List<Path> workerClasspath,
        Path provisionCacheDir,
        long timeoutMinutes,
        boolean skipProvision,
        boolean help) {

    public static final String DEFAULT_MC_VERSION = "1.20.1";
    public static final String DEFAULT_LOADER_VERSION = "0.16.14";
    public static final String DEFAULT_WORK_DIR = "./work";
    public static final long DEFAULT_TIMEOUT_MINUTES = 15;

    public static final String USAGE = """
            harvestModels —— headless 采集 Minecraft BakedModel 并导出 models.json.gz

            用法: gradle :apps:voxelith-server:harvestModels -PpackDir=<jar> [-Pmods=<jar,jar>] [...]

            选项（Gradle 用 -P<name>=<value>，直接跑 main 时用 --<name>）:
              mcVersion        目标 MC 版本（默认 %s）
              loaderVersion    Fabric loader 版本（默认 %s）
              workDir          产物与报告目录（默认 %s）；产出 models.json.gz 与 model-acquisition.json
              packDir          必填：兜底静态解析用的资源 jar（通常是原版 client jar）
              mods             mod jar 路径，逗号分隔；其方块会随采集一并导出
              provisionCache   MC/Fabric 依赖下载缓存（默认 <workDir>/.provision-cache）
              timeoutMinutes   worker 超时分钟数（默认 %d）
              skipProvision    true = 跳过下载与 Knot 引导，仅跑 LWJGL 自检（排障用）
            """.formatted(DEFAULT_MC_VERSION, DEFAULT_LOADER_VERSION, DEFAULT_WORK_DIR, DEFAULT_TIMEOUT_MINUTES);

    public HarvestCliOptions {
        modJars = modJars == null ? List.of() : List.copyOf(modJars);
        workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
    }

    /**
     * @throws IllegalArgumentException 参数缺失或格式非法
     */
    public static HarvestCliOptions parse(String[] args) {
        String mcVersion = DEFAULT_MC_VERSION;
        String loaderVersion = DEFAULT_LOADER_VERSION;
        String workDir = DEFAULT_WORK_DIR;
        String assetJar = null;
        String provisionCache = null;
        long timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
        boolean skipProvision = false;
        boolean help = false;
        List<String> mods = new ArrayList<>();
        List<String> workerClasspath = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "--mc-version", "-mcVersion" -> mcVersion = value(args, ++i, arg);
                case "--loader-version", "-loaderVersion" -> loaderVersion = value(args, ++i, arg);
                case "--work-dir", "-workDir" -> workDir = value(args, ++i, arg);
                case "--pack-dir", "-packDir" -> assetJar = value(args, ++i, arg);
                case "--provision-cache", "-provisionCache" -> provisionCache = value(args, ++i, arg);
                case "--timeout-minutes", "-timeoutMinutes" ->
                        timeoutMinutes = parseLong(value(args, ++i, arg), arg);
                case "--mod", "-mods" -> mods.add(value(args, ++i, arg));
                case "--worker-classpath", "-workerClasspath" ->
                        workerClasspath.add(value(args, ++i, arg));
                case "--skip-provision", "-skipProvision" -> skipProvision = true;
                default -> throw new IllegalArgumentException("未知参数: " + arg);
            }
        }
        if (help) {
            return new HarvestCliOptions(mcVersion, loaderVersion, Path.of(workDir),
                    assetJar == null ? null : Path.of(assetJar), List.of(), List.of(),
                    null, timeoutMinutes, skipProvision, true);
        }
        if (assetJar == null || assetJar.isBlank()) {
            throw new IllegalArgumentException(
                    "缺少 packDir（兜底静态解析用的资源 jar，通常是原版 client jar）");
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("缺少 worker-classpath（worker 子 JVM 的 classpath）");
        }
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("timeoutMinutes 必须为正数");
        }
        Path work = Path.of(workDir);
        Path cache = provisionCache == null || provisionCache.isBlank()
                ? work.resolve(".provision-cache")
                : Path.of(provisionCache);
        return new HarvestCliOptions(
                mcVersion,
                loaderVersion,
                work,
                Path.of(assetJar),
                splitPaths(mods),
                splitPaths(workerClasspath),
                cache,
                timeoutMinutes,
                skipProvision,
                false);
    }

    private static String value(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("参数 " + name + " 缺少取值");
        }
        return args[index];
    }

    private static long parseLong(String raw, String name) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 需要整数，收到: " + raw);
        }
    }

    /** 逗号/分号分隔的路径列表。 */
    private static List<Path> splitPaths(List<String> raw) {
        List<Path> paths = new ArrayList<>();
        for (String item : raw) {
            for (String piece : item.split("[,;]")) {
                if (!piece.isBlank()) {
                    paths.add(Path.of(piece.trim()));
                }
            }
        }
        return paths;
    }
}
