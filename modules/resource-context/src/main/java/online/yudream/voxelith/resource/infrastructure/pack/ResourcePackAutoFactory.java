package online.yudream.voxelith.resource.infrastructure.pack;

import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.resource.domain.pack.ResourcePackFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 按载体自动选择资源包实现：目录 → DirectoryResourcePack；.jar/.zip → ZipResourcePack。
 */
public final class ResourcePackAutoFactory implements ResourcePackFactory {

    @Override
    public ResourcePack open(Path source, int priority) {
        if (Files.isDirectory(source)) {
            return new DirectoryResourcePack(source, priority);
        }
        String name = source.getFileName().toString().toLowerCase();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            return new ZipResourcePack(source, priority);
        }
        throw new IllegalArgumentException("无法识别的资源包载体: " + source);
    }
}
