package online.yudream.voxelith.resource.infrastructure.pack;

import online.yudream.voxelith.resource.domain.pack.AssetPaths;
import online.yudream.voxelith.resource.domain.pack.PackResource;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 以解压目录为载体的资源包（目录下直接是 assets/ 结构）。
 */
public final class DirectoryResourcePack implements ResourcePack, PackStack.Prioritized {

    private final Path root;
    private final int priority;

    public DirectoryResourcePack(Path root, int priority) {
        this.root = root;
        this.priority = priority;
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("不是目录: " + root);
        }
    }

    @Override
    public String packId() {
        return root.getFileName().toString();
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
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return blocks;
        }
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path nsDir : namespaces.filter(Files::isDirectory).toList()) {
                Path blockstates = nsDir.resolve("blockstates");
                if (!Files.isDirectory(blockstates)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(blockstates)) {
                    files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                        String rel = blockstates.relativize(f).toString().replace('\\', '/');
                        blocks.add(AssetPaths.blockFromBlockstatePath(nsDir.getFileName().toString(), rel));
                    });
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("枚举 blockstates 失败: " + root, e);
        }
        return blocks;
    }

    private Optional<PackResource> read(String relative) {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PackResource(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败: " + file, e);
        }
    }
}
