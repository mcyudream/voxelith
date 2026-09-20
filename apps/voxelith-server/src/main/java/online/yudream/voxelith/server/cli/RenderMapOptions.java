package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.BakeCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * renderMap 入口参数：把一个存档的指定区域窗口渲染成可发布的地图（resolve→bake→tile→lod→manifest）。
 *
 * @param worldDir      存档根目录（含 region/）
 * @param dimension     维度 id（minecraft:overworld / the_nether / the_end）
 * @param mapId         地图 id（发布到 {publishDir}/{mapId}）
 * @param mapName       展示名
 * @param packs         资源包路径，按优先级从低到高（原版 client jar → mod jar）
 * @param workDir       中间产物目录（tiles/ 与高度场）
 * @param publishDir    发布根目录
 * @param modelsFile    runtime 采集的 models.json.gz；可空 = 纯静态模型解析
 * @param regionX0/X1/regionZ0/Z1 区域窗口（含端点，region 坐标）；四个都缺省 = 全部有内容的 region
 * @param maxLevel      LOD 最高层级；0 = 自动（聚合到全图 ≤2×2 瓦片）
 * @param sampleChunks  bake 落盘样本区块数
 * @param lodAtlas      是否生成 LOD 分层图集页
 * @param minY          最低渲染高度（含）；低于它的方块不参与网格化（地下洞穴/矿层对地表地图无用）
 * @param minX/maxX/minZ/maxZ 要渲染的方块范围（含端点，null = 不裁剪）。region 窗口是按 512 方块
 *                     取整的，按方块范围裁剪才能刚好只渲染目标区域（例如一个学校）
 * @param mcVersion     采集用的 MC 版本（默认 {@link #DEFAULT_MC_VERSION}）
 * @param loaderVersion 采集用的 Fabric loader 版本（默认 {@link #DEFAULT_LOADER_VERSION}）
 * @param skipHarvest   true = 不跑采集、纯静态模型解析。默认先采集一次并缓存到
 *                     {@code <workDir>/models.json.gz}：静态解析在 uvlock / 元素旋转等处与原版有偏差，
 *                     而采集产物是游戏自己烘焙的几何，按定义正确（约 1 分钟，命中缓存即跳过）
 * @param workerClasspath worker 子 JVM 的基础 classpath（由 Gradle 任务注入，采集用）
 * @param maxChunks     单次渲染允许的最大非空区块数（0 = 不限制）。bake 会把窗口内全部区块的
 *                      网格留在内存里，整图级别的窗口（几万区块）必然 OOM——超限时管线直接
 *                      拒绝并给出缩范围建议，而不是跑一半把服务进程拖死
 * @param batchChunks   多遍渲染：窗口超过这个区块数就切成若干批，每批单独 bake→tile→lod 并共用一张
 *                      预打好的图集（0 = 不分遍）。内存只与单批大小相关，因此窗口可以任意大，
 *                      代价是时间与磁盘
 * @param meshopt       true = 瓦片几何走 EXT_meshopt_compression 熵编码（默认开）。
 *                      实测 64×64 网格瓦片 BIN 从 258856B 降到 50228B（约 1/5）；
 *                      前端 GLTFLoader 已挂 meshopt 解码器，老瓦片（未压缩）仍可混着读
 */
public record RenderMapOptions(
        Path worldDir,
        String dimension,
        String mapId,
        String mapName,
        List<Path> packs,
        Path workDir,
        Path publishDir,
        Path modelsFile,
        Integer regionX0,
        Integer regionX1,
        Integer regionZ0,
        Integer regionZ1,
        int maxLevel,
        int sampleChunks,
        boolean lodAtlas,
        int minY,
        Integer minX,
        Integer maxX,
        Integer minZ,
        Integer maxZ,
        String mcVersion,
        String loaderVersion,
        boolean skipHarvest,
        List<Path> workerClasspath,
        int maxChunks,
        int batchChunks,
        boolean meshopt) {

    public static final String DEFAULT_MC_VERSION = "1.20.1";
    public static final String DEFAULT_LOADER_VERSION = "0.16.14";

    public static final String USAGE = """
            renderMap —— 把存档渲染为可发布地图（采集→bake→tile→lod→manifest）

            用法: gradle :apps:voxelith-server:renderMap -PworldDir=<存档> -PmapId=<id> -Ppacks=<jar,jar> [...]

            选项（Gradle 用 -P<name>=<value>，直接跑 main 时用 --<kebab-name>）:
              worldDir         必填：存档根目录（含 region/ 与 level.dat）
              mapId            必填：地图 id，发布到 <publishDir>/<mapId>
              packs            必填：资源包路径，逗号分隔，从低到高优先级。
                               约定第一个是原版 client jar，其余是 mod jar——采集时按此拆分
                               自动跑一次 headless 采集（缓存到 <workDir>/models.json.gz）
              mapName          展示名（默认取 mapId）
              dimension        维度（默认 minecraft:overworld）
              workDir          中间产物目录（默认 ./work）
              publishDir       发布根目录（默认 ./data/maps）
              mcVersion        采集的 MC 版本（默认 %s）
              loaderVersion    采集的 Fabric loader 版本（默认 %s）
              skipHarvest      true = 不采集，纯静态模型解析（uvlock/旋转等处与原版有偏差）
              modelsFile       直接指定 models.json.gz 路径；留空 = 用 <workDir>/models.json.gz 或自动采集
              regionX0/X1/Z0/Z1  region 窗口（含端点）。只给部分时未给的边取 region 文件的极值
              maxLevel         LOD 最高层级（0 = 自动，默认 0）
              sampleChunks     bake 落盘样本区块数（默认 0）
              minY             最低渲染高度（含）；低于它的方块不参与网格化
              minX/maxX/minZ/maxZ  要渲染的方块范围（含端点）；与 region 窗口叠加使用
              noLodAtlas       true = 不生成 LOD 分层图集页（逐瓦片内嵌色图）
              noMeshopt        true = 关闭 EXT_meshopt_compression 熵编码（默认开启；
                               量化 + 熵编码可把瓦片几何压到未压缩的约 1/5）
              maxChunks        单次渲染允许的最大非空区块数（0 = 不限制）。超过就拒绝并提示缩小范围
              batchChunks      多遍渲染：超过这个区块数就分遍跑（0 = 不分遍）。内存只与单批大小相关
            """.formatted(DEFAULT_MC_VERSION, DEFAULT_LOADER_VERSION);

    public RenderMapOptions {
        packs = packs == null ? List.of() : List.copyOf(packs);
        workerClasspath = workerClasspath == null ? List.of() : List.copyOf(workerClasspath);
        mcVersion = mcVersion == null || mcVersion.isBlank() ? DEFAULT_MC_VERSION : mcVersion;
        loaderVersion = loaderVersion == null || loaderVersion.isBlank()
                ? DEFAULT_LOADER_VERSION : loaderVersion;
        if (worldDir == null) {
            throw new IllegalArgumentException("缺少 worldDir");
        }
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("缺少 mapId");
        }
        if (packs.isEmpty()) {
            throw new IllegalArgumentException("缺少 packs（至少需要原版 client jar）");
        }
        mapName = mapName == null || mapName.isBlank() ? mapId : mapName;
        dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
        workDir = workDir == null ? Path.of("./work") : workDir;
        publishDir = publishDir == null ? Path.of("./data/maps") : publishDir;
    }

    /** 是否限定了 region 窗口。 */
    public boolean hasWindow() {
        return regionX0 != null || regionX1 != null || regionZ0 != null || regionZ1 != null;
    }

    /** @throws IllegalArgumentException 参数缺失或格式非法 */
    public static RenderMapOptions parse(String[] args) {
        String worldDir = null;
        String dimension = null;
        String mapId = null;
        String mapName = null;
        String packs = null;
        String workDir = null;
        String publishDir = null;
        String modelsFile = null;
        Integer x0 = null, x1 = null, z0 = null, z1 = null;
        int maxLevel = 0;
        int sampleChunks = 0;
        boolean lodAtlas = true;
        int minY = BakeCommand.NO_MIN_Y;
        Integer minX = null, maxX = null, minZ = null, maxZ = null;
        String mcVersion = null;
        String loaderVersion = null;
        boolean skipHarvest = false;
        List<String> workerClasspath = new ArrayList<>();
        int maxChunks = 0;
        int batchChunks = 0;
        boolean meshopt = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--world-dir", "-worldDir" -> worldDir = value(args, ++i, arg);
                case "--dimension", "-dimension" -> dimension = value(args, ++i, arg);
                case "--map-id", "-mapId" -> mapId = value(args, ++i, arg);
                case "--map-name", "-mapName" -> mapName = value(args, ++i, arg);
                case "--packs", "-packs" -> packs = value(args, ++i, arg);
                case "--work-dir", "-workDir" -> workDir = value(args, ++i, arg);
                case "--publish-dir", "-publishDir" -> publishDir = value(args, ++i, arg);
                case "--models-file", "-modelsFile" -> modelsFile = value(args, ++i, arg);
                case "--region-x0", "-regionX0" -> x0 = intValue(args, ++i, arg);
                case "--region-x1", "-regionX1" -> x1 = intValue(args, ++i, arg);
                case "--region-z0", "-regionZ0" -> z0 = intValue(args, ++i, arg);
                case "--region-z1", "-regionZ1" -> z1 = intValue(args, ++i, arg);
                case "--max-level", "-maxLevel" -> maxLevel = intValue(args, ++i, arg);
                case "--sample-chunks", "-sampleChunks" -> sampleChunks = intValue(args, ++i, arg);
                case "--min-y", "-minY" -> minY = intValue(args, ++i, arg);
                case "--min-x", "-minX" -> minX = intValue(args, ++i, arg);
                case "--max-x", "-maxX" -> maxX = intValue(args, ++i, arg);
                case "--min-z", "-minZ" -> minZ = intValue(args, ++i, arg);
                case "--max-z", "-maxZ" -> maxZ = intValue(args, ++i, arg);
                case "--no-lod-atlas", "-noLodAtlas" -> lodAtlas = false;
                case "--no-meshopt", "-noMeshopt" -> meshopt = false;
                case "--mc-version", "-mcVersion" -> mcVersion = value(args, ++i, arg);
                case "--loader-version", "-loaderVersion" -> loaderVersion = value(args, ++i, arg);
                case "--skip-harvest", "-skipHarvest" -> skipHarvest = true;
                case "--max-chunks", "-maxChunks" -> maxChunks = intValue(args, ++i, arg);
                case "--batch-chunks", "-batchChunks" -> batchChunks = intValue(args, ++i, arg);
                case "--worker-classpath", "-workerClasspath" ->
                        workerClasspath.add(value(args, ++i, arg));
                default -> throw new IllegalArgumentException("未知参数: " + arg);
            }
        }

        List<Path> packPaths = new ArrayList<>();
        if (packs != null) {
            for (String piece : packs.split("[,;]")) {
                if (!piece.isBlank()) {
                    packPaths.add(Path.of(piece.trim()));
                }
            }
        }
        return new RenderMapOptions(
                worldDir == null ? null : Path.of(worldDir),
                dimension,
                mapId,
                mapName,
                packPaths,
                workDir == null ? null : Path.of(workDir),
                publishDir == null ? null : Path.of(publishDir),
                modelsFile == null || modelsFile.isBlank() ? null : Path.of(modelsFile),
                x0, x1, z0, z1,
                maxLevel, sampleChunks, lodAtlas, minY, minX, maxX, minZ, maxZ,
                mcVersion, loaderVersion, skipHarvest, pathsOf(workerClasspath),
                Math.max(0, maxChunks), Math.max(0, batchChunks), meshopt);
    }

    /**
     * 反向序列化成 CLI 参数（与 {@link #parse} 成对）。
     *
     * <p>服务端网页触发的渲染把任务交给独立 JVM 子进程跑（渲染崩了/内存爆了不会带走 web 进程，
     * 也能给渲染单独配堆），参数就从这里生成；与 {@code parse} 放在同一个文件里，
     * 加字段时不容易只改一半。</p>
     */
    public List<String> toArgs() {
        List<String> args = new ArrayList<>();
        add(args, "--world-dir", worldDir.toString());
        add(args, "--dimension", dimension);
        add(args, "--map-id", mapId);
        add(args, "--map-name", mapName);
        add(args, "--packs", packs.stream().map(Path::toString).collect(Collectors.joining(",")));
        add(args, "--work-dir", workDir.toString());
        add(args, "--publish-dir", publishDir.toString());
        if (modelsFile != null) {
            add(args, "--models-file", modelsFile.toString());
        }
        addIfPresent(args, "--region-x0", regionX0);
        addIfPresent(args, "--region-x1", regionX1);
        addIfPresent(args, "--region-z0", regionZ0);
        addIfPresent(args, "--region-z1", regionZ1);
        add(args, "--max-level", Integer.toString(maxLevel));
        add(args, "--sample-chunks", Integer.toString(sampleChunks));
        if (!lodAtlas) {
            args.add("--no-lod-atlas");
        }
        add(args, "--min-y", Integer.toString(minY));
        addIfPresent(args, "--min-x", minX);
        addIfPresent(args, "--max-x", maxX);
        addIfPresent(args, "--min-z", minZ);
        addIfPresent(args, "--max-z", maxZ);
        add(args, "--mc-version", mcVersion);
        add(args, "--loader-version", loaderVersion);
        if (skipHarvest) {
            args.add("--skip-harvest");
        }
        for (Path entry : workerClasspath) {
            add(args, "--worker-classpath", entry.toString());
        }
        if (maxChunks > 0) {
            add(args, "--max-chunks", Integer.toString(maxChunks));
        }
        if (batchChunks > 0) {
            add(args, "--batch-chunks", Integer.toString(batchChunks));
        }
        if (!meshopt) {
            args.add("--no-meshopt");
        }
        return args;
    }

    private static void add(List<String> args, String name, String value) {
        args.add(name);
        args.add(value);
    }

    private static void addIfPresent(List<String> args, String name, Integer value) {
        if (value != null) {
            add(args, name, Integer.toString(value));
        }
    }

    /** classpath 项（一条一个路径，Gradle 会逐项传）→ 路径列表。 */
    private static List<Path> pathsOf(List<String> raw) {
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

    private static String value(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("参数 " + name + " 缺少取值");
        }
        return args[index];
    }

    private static int intValue(String[] args, int index, String name) {
        String raw = value(args, index, name);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 需要整数，收到: " + raw);
        }
    }
}
