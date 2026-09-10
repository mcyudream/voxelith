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
 */
public final class ZipResourcePack implements ResourcePack, PackStack.Prioritized {

    private final Path file;
    private final int priority;
    private final ZipFile zip;

    public ZipResourcePack(Path file, int priority) {
        this.file = file;
        this.priority = priority;
        try {
            this.zip = new ZipFile(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开资源包: " + file, e);
        }
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
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
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

    private Optional<PackResource> read(String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
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
