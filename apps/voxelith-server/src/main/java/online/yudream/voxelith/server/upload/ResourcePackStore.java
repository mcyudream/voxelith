package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.resource.infrastructure.pack.DirectoryResourcePack;
import online.yudream.voxelith.resource.infrastructure.pack.ZipResourcePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 材质包（资源包）仓库：上传的 zip 落盘到受管目录，本机目录/文件只登记不复制。
 *
 * <p>渲染请求里的材质包以**路径**为准（受管条目给绝对路径，本机登记条目给登记时的路径）。
 * 上传与登记都会做一次可读性校验（zip 能打开、能探测出资源根），把「包是坏的」这类
 * 错误拦在登记时，而不是留到几小时渲染跑完才发现整图品红。</p>
 */
public class ResourcePackStore {

    private static final Logger log = LoggerFactory.getLogger(ResourcePackStore.class);

    private final Path dir;

    public ResourcePackStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建材质包目录: " + this.dir, e);
        }
    }

    /** 受管目录（服务端配置里可见）。 */
    public Path dir() {
        return dir;
    }

    /** 单个材质包的登记信息（前端列表展示用）。 */
    public record PackInfo(String name, String path, long sizeBytes, boolean managed,
                           boolean zip, String layout) {
    }

    /** 受管目录里的全部材质包（按文件名排序）。 */
    public List<PackInfo> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.list(dir)) {
            List<PackInfo> result = new ArrayList<>();
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                try {
                    result.add(info(p, true));
                } catch (RuntimeException e) {
                    log.warn("跳过无法读取的材质包: {}", p, e);
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描材质包目录失败: " + dir, e);
        }
    }

    /**
     * 保存上传的材质包 zip 到受管目录，并做可读性校验。
     *
     * @throws IllegalArgumentException 文件为空 / 不是 zip / zip 打不开
     */
    public PackInfo saveUpload(InputStream in, String originalFilename) throws IOException {
        String name = sanitizeName(originalFilename);
        Path target = dir.resolve(name);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        try {
            PackInfo info = info(target, true);
            log.info("材质包已上传：{}（布局 {}）", target, info.layout());
            return info;
        } catch (RuntimeException e) {
            // 校验失败不留坏文件在受管目录里
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignore) {
                // 删除失败也以校验错误为主
            }
            throw e;
        }
    }

    /** 登记本机已有的材质包（zip 或目录，不复制）。 */
    public PackInfo registerLocal(String rawPath) {
        Path path = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("材质包不存在: " + path);
        }
        return info(path, false);
    }

    /** 删除受管目录里的一个材质包 zip。 */
    public boolean deleteManaged(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            throw new IllegalArgumentException("非法的材质包名: " + name);
        }
        Path target = dir.resolve(name);
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try {
            Files.deleteIfExists(target);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("删除材质包失败: " + target, e);
        }
    }

    /** 组装登记信息；zip 打不开 / 目录里没有 assets 时抛 IllegalArgumentException。 */
    private PackInfo info(Path path, boolean managed) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (Files.isDirectory(path)) {
            validateDirectory(path);
            return new PackInfo(name, path.toString(), 0, managed, false, "directory");
        }
        if (!lower.endsWith(".zip")) {
            throw new IllegalArgumentException("材质包须是 .zip 文件或目录: " + name);
        }
        try (ZipResourcePack pack = new ZipResourcePack(path, 0)) {
            int blocks = pack.listBlocksWithBlockstate().size();
            String prefix = pack.rootPrefix();
            if (blocks == 0 && prefix.isEmpty()) {
                if (ZipResourcePack.hasBackslashEntries(path)) {
                    throw new IllegalArgumentException(
                            "这个 zip 的内部路径用了反斜杠（Windows 右键「压缩到 zip」的产物），"
                                    + "Minecraft 本体也读不了它：请用 7-Zip/Bandizip 重新打包，"
                                    + "或把解压出来的文件夹直接登记为本机目录: " + name);
                }
                throw new IllegalArgumentException(
                        "zip 里没有找到资源（需要 pack.mcmeta 与 assets/ 目录）: " + name);
            }
            String layout = prefix.isEmpty() ? "root" : "nested:" + prefix.substring(0, prefix.length() - 1);
            return new PackInfo(name, path.toString(), fileSize(path), managed, true, layout);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法打开材质包 zip: " + name, e);
        } catch (UncheckedIOException e) {
            throw new IllegalArgumentException("无法打开材质包 zip: " + name, e.getCause());
        }
    }

    private static void validateDirectory(Path path) {
        if (!Files.isDirectory(path.resolve("assets"))) {
            throw new IllegalArgumentException(
                    "目录里没有 assets/，不是可用的材质包目录: " + path.getFileName());
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 上传文件名只保留文件名部分并强制 .zip 后缀（与 WorldStore 的存档命名同风格）。 */
    static String sanitizeName(String original) {
        String name = original == null ? "" : original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("缺少材质包文件名");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name + ".zip";
        }
        return name;
    }
}
