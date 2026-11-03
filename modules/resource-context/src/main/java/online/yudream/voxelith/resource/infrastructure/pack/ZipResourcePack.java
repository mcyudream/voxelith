package online.yudream.voxelith.resource.infrastructure.pack;

import online.yudream.voxelith.resource.domain.pack.AssetPaths;
import online.yudream.voxelith.resource.domain.pack.PackResource;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 以 zip/jar 文件为载体的资源包（原版客户端 jar、资源包 zip 均适用）。
 *
 * <p>社区材质包 zip 常见两种布局：{@code assets/...} 直接在根上，或整体包在一层
 * {@code PackName/} 子目录里（打包时把文件夹拖进了压缩包）。原版只认前者，这里两者都认：
 * 构造时探测 {@code pack.mcmeta}（退化时探测 {@code assets/}）所在的层级，
 * 之后所有读取都自动带上该前缀——用户不需要先解包重压一遍。</p>
 */
public final class ZipResourcePack implements ResourcePack, PackStack.Prioritized, AutoCloseable {

    private final Path file;
    private final int priority;
    private final ZipFile zip;
    /** 资源在 zip 内的实际前缀（"" 或 "PackName/"），探测规则见类注释。 */
    private final String rootPrefix;

    public ZipResourcePack(Path file, int priority) {
        this.file = file;
        this.priority = priority;
        try {
            this.zip = new ZipFile(file.toFile());
            this.rootPrefix = detectRootPrefix(zip);
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开资源包: " + file, e);
        }
    }

    /** 探测资源根：pack.mcmeta 优先，退化看 assets/；都不在子目录里就是根。 */
    static String detectRootPrefix(ZipFile zip) {
        var entries = zip.entries();
        String assetsPrefix = null;
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.equals("pack.mcmeta")) {
                return "";
            }
            // pack.mcmeta 在恰好一层子目录下（PackName/pack.mcmeta）
            int meta = name.indexOf("/pack.mcmeta");
            if (meta > 0 && name.indexOf('/') == meta) {
                return name.substring(0, meta + 1);
            }
            // 记住第一个「assets/ 前只有一层目录」的候选（PackName/assets/...）
            if (assetsPrefix == null) {
                int i = name.indexOf("/assets/");
                if (i > 0 && name.indexOf('/') == i) {
                    assetsPrefix = name.substring(0, i + 1);
                }
            }
        }
        return assetsPrefix != null ? assetsPrefix : "";
    }

    /**
     * zip 里是否存在反斜杠条目名（Windows 右键「压缩」/PowerShell 5.1 的产物）。
     * 原版 Minecraft 同样读不了这种包，报错时要与「真的缺资源」区分开。
     */
    public static boolean hasBackslashEntries(Path zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().indexOf('\\') >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String packId() {
        return file.getFileName().toString();
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Optional<PackResource> blockstate(Identifier block) {
        return read(AssetPaths.blockstate(block));
    }

    @Override
    public Optional<PackResource> model(Identifier model) {
        return read(AssetPaths.model(model));
    }

    @Override
    public Optional<PackResource> texture(Identifier texture) {
        return read(AssetPaths.texture(texture));
    }

    @Override
    public Optional<PackResource> raw(String path) {
        return read(path);
    }

    @Override
    public Set<Identifier> listBlocksWithBlockstate() {
        Set<Identifier> blocks = new HashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (rootPrefix != null && !rootPrefix.isEmpty()) {
                if (!name.startsWith(rootPrefix)) {
                    continue;
                }
                name = name.substring(rootPrefix.length());
            }
            // assets/{ns}/blockstates/{path}.json
            if (!name.startsWith("assets/") || !name.contains("/blockstates/") || !name.endsWith(".json")) {
                continue;
            }
            int nsEnd = name.indexOf('/', "assets/".length());
            String namespace = name.substring("assets/".length(), nsEnd);
            String fileName = name.substring(name.indexOf("/blockstates/") + "/blockstates/".length());
            blocks.add(AssetPaths.blockFromBlockstatePath(namespace, fileName));
        }
        return blocks;
    }

    /** 资源在 zip 内的实际前缀（"" 或 "PackName/"），服务端登记材质包时用它提示布局。 */
    public String rootPrefix() {
        return rootPrefix;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    private Optional<PackResource> read(String entryName) {
        ZipEntry entry = zip.getEntry(rootPrefix + entryName);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PackResource(zip.getInputStream(entry).readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException("读取资源包条目失败: " + file + "!" + entryName, e);
        }
    }
}
