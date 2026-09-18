package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.infrastructure.bootstrap.ResourceContextBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 渲染输入资源的解析与共享：资源包列表、采集产物、worker classpath。
 *
 * <p>上传/预览/渲染三条链路都要「原版 client jar + mod jar」这套资源包栈，解析规则写在一处，
 * 避免三处各写一份而漂移。资源目录（{@link ResolvedResourceCatalog}）按包列表缓存复用——
 * 解析 blockstate/model 很贵，每次预览都重建会让第一次点击等上十几秒。</p>
 */
public class RenderInputs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RenderInputs.class);

    private final Path workDir;
    private final List<Path> configuredPacks;
    private final Path configuredModelsFile;
    private final List<Path> configuredWorkerClasspath;
    private final String mcVersion;
    private final String loaderVersion;

    private ResolvedResourceCatalog catalog;
    private List<Path> catalogPacks;

    public RenderInputs(Path workDir, List<Path> packs, Path modelsFile,
                        List<Path> workerClasspath, String mcVersion, String loaderVersion) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.configuredPacks = List.copyOf(packs);
        this.configuredModelsFile = modelsFile;
        this.configuredWorkerClasspath = List.copyOf(workerClasspath);
        this.mcVersion = mcVersion;
        this.loaderVersion = loaderVersion;
    }

    public Path workDir() {
        return workDir;
    }

    public String mcVersion() {
        return mcVersion;
    }

    public String loaderVersion() {
        return loaderVersion;
    }

    /**
     * 资源包栈（低 → 高优先级）。配置为空时自动发现本机 client jar，
     * 再找不到就返回空列表（预览退化为内置色表，渲染会明确报错要求指定资源包）。
     */
    public List<Path> packs() {
        if (!configuredPacks.isEmpty()) {
            return configuredPacks.stream().filter(Files::exists).toList();
        }
        return discoverClientJars();
    }

    /** 配置里显式给的采集产物；留空时在 work-dir 下探测最新的 models.json.gz。 */
    public Optional<Path> modelsFile() {
        if (configuredModelsFile != null && Files.isRegularFile(configuredModelsFile)) {
            return Optional.of(configuredModelsFile);
        }
        return discoverModels();
    }

    /**
     * worker 子进程 classpath：配置优先，否则自动发现仓库内的 runtime-worker 编译产物
     * （gson 从服务端自己的 classpath 上找）。找不到返回空列表——渲染任务据此退回静态解析。
     */
    public List<Path> workerClasspath() {
        if (!configuredWorkerClasspath.isEmpty()) {
            return configuredWorkerClasspath.stream().filter(Files::exists).toList();
        }
        return discoverWorkerClasspath();
    }

    /** 从 work-dir 逐级上溯找仓库内的 runtime-worker 编译产物。 */
    private List<Path> discoverWorkerClasspath() {
        Path cursor = workDir;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path classes = cursor.resolve("modules/runtime-worker/build/classes/java/main");
            if (Files.isDirectory(classes)) {
                List<Path> classpath = new ArrayList<>();
                classpath.add(classes);
                for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                    if (entry.endsWith(".jar") && entry.contains("gson")) {
                        classpath.add(Path.of(entry));
                        break;
                    }
                }
                log.info("worker classpath 自动发现：{}", classes);
                return List.copyOf(classpath);
            }
            cursor = cursor.getParent();
        }
        log.warn("未找到 runtime-worker 编译产物，渲染任务将退回静态解析（无 uvlock/元素旋转修正）");
        return List.of();
    }

    /**
     * 共享资源目录（懒加载 + 按包列表缓存）。没有可用资源包时返回 {@code null}，
     * 调用方据此走内置色表兜底而不是抛异常。
     */
    public synchronized ResolvedResourceCatalog catalog() {
        List<Path> packs = packs();
        if (packs.isEmpty()) {
            return null;
        }
        if (catalog != null && packs.equals(catalogPacks)) {
            return catalog;
        }
        if (catalog != null) {
            catalog.close();
            catalog = null;
        }
        long t0 = System.currentTimeMillis();
        catalog = ResourceContextBootstrap.openCatalog(packs);
        catalogPacks = packs;
        log.info("资源目录已就绪：{} 个包，耗时 {}ms", packs.size(),
                System.currentTimeMillis() - t0);
        return catalog;
    }

    /**
     * 自动发现原版 client jar：先看仓库内的 {@code .cache/minecraft}（采集缓存），
     * 再看用户目录下的 {@code .minecraft/versions}。找不到返回空列表。
     */
    public List<Path> discoverClientJars() {
        List<Path> all = discoverAllClientJars();
        return all.isEmpty() ? List.of() : List.of(all.getFirst());
    }

    /** 探测可用的 client jar（供前端下拉候选，按修改时间倒序）。 */
    public List<Path> discoverAllClientJars() {
        List<Path> found = new ArrayList<>();
        for (Path root : candidateCacheRoots()) {
            found.addAll(collectJars(root, 1, name -> name.startsWith("client-")));
        }
        Path minecraftHome = Path.of(System.getProperty("user.home", "."), ".minecraft", "versions");
        found.addAll(collectJars(minecraftHome, 2, name -> true));
        found.sort(Comparator.comparingLong(RenderInputs::lastModified).reversed());
        return found;
    }

    /**
     * 按存档版本挑资源包栈（低 → 高优先级）。
     *
     * <p>贴图名是跟着版本走的：1.20.3 起 {@code grass} 改名 {@code short_grass}，
     * 所以拿 1.20.1 的 jar 去渲染 1.21.1 的存档，草地这类方块找不到贴图，会整片变成
     * 品红兜底色。因此**版本号匹配的那份 jar 放最高优先级**。</p>
     *
     * <p>为什么不干脆只用匹配的那一份：采集产物 models.json.gz 可能沿用别的版本
     * （例如之前某次 1.20.4 采集的结果），它的贴图名属于它自己的版本。把缓存里其它
     * client jar 垫在下面当补缺，两边都能兜住——垫在下面的包只在上面找不到同名贴图时生效，
     * 不会改变已经有贴图的方块外观。</p>
     *
     * @param worldVersion 存档版本（如 {@code 1.21.1}）；匹配不到就退回默认资源包
     */
    public List<Path> packsForVersion(String worldVersion) {
        Path matched = matchVersionJar(worldVersion);
        if (matched == null) {
            return packs();
        }
        List<Path> result = new ArrayList<>();
        for (Path jar : cacheClientJars()) {
            if (!jar.equals(matched)) {
                result.add(jar);
            }
        }
        for (Path pack : configuredPacks) {
            if (Files.isRegularFile(pack) && !result.contains(pack) && !pack.equals(matched)) {
                result.add(pack);
            }
        }
        result.add(matched);
        return List.copyOf(result);
    }

    /** 缓存目录里名字正好是 {@code client-<版本>.jar} 的那份原版 jar。 */
    private Path matchVersionJar(String worldVersion) {
        if (worldVersion == null || worldVersion.isBlank()) {
            return null;
        }
        String expected = "client-" + worldVersion.trim() + ".jar";
        for (Path jar : cacheClientJars()) {
            if (jar.getFileName().toString().equals(expected)) {
                return jar;
            }
        }
        return null;
    }

    /** 仓库缓存（.cache/minecraft）里的原版 jar，按文件名排序以便结果稳定可复现。 */
    private List<Path> cacheClientJars() {
        List<Path> jars = new ArrayList<>();
        for (Path root : candidateCacheRoots()) {
            jars.addAll(collectJars(root, 1, name -> name.startsWith("client-")));
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return jars;
    }

    /** work-dir 下最新的一份 models.json.gz（采集产物常落在 work/<mapId>-harvest/）。 */
    public Optional<Path> discoverModels() {
        Path direct = workDir.resolve("models.json.gz");
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        if (!Files.isDirectory(workDir)) {
            return Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workDir, 3)) {
            walk.filter(p -> p.getFileName().toString().equals("models.json.gz"))
                    .filter(Files::isRegularFile)
                    .forEach(candidates::add);
        } catch (IOException e) {
            log.warn("探测采集产物失败: {}", workDir, e);
        }
        return candidates.stream().max(Comparator.comparingLong(RenderInputs::lastModified));
    }

    /** 仓库根方向的采集缓存目录：从 work-dir 逐级上溯找 .cache/minecraft。 */
    private List<Path> candidateCacheRoots() {
        List<Path> roots = new ArrayList<>();
        Path cursor = workDir;
        for (int i = 0; i < 4 && cursor != null; i++) {
            Path cache = cursor.resolve(".cache").resolve("minecraft");
            if (Files.isDirectory(cache)) {
                roots.add(cache);
            }
            cursor = cursor.getParent();
        }
        return roots;
    }

    private static List<Path> collectJars(Path root, int depth, Predicate<String> nameFilter) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, depth)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> nameFilter.test(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 释放共享资源目录（应用关闭时）。 */
    @Override
    public synchronized void close() {
        if (catalog != null) {
            catalog.close();
            catalog = null;
            catalogPacks = null;
        }
    }
}
