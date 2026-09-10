package online.yudream.voxelith.tile.infrastructure.artifact;

import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.application.PublishedAtlas;
import online.yudream.voxelith.tile.domain.atlas.AtlasLayout;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker.AtlasResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 已发布图集落盘：{publishRoot}/{mapId}/atlas.png + atlas-layout.json。
 * 增量重跑只读这两份文件，不重打包。
 */
public final class FilePublishedAtlas implements PublishedAtlas {

    private final Path publishRoot;

    public FilePublishedAtlas(Path publishRoot) {
        this.publishRoot = publishRoot;
    }

    @Override
    public Optional<AtlasReuse> load(String mapId) {
        Path mapDir = publishRoot.resolve(mapId);
        Path pngFile = mapDir.resolve("atlas.png");
        Path layoutFile = mapDir.resolve(AtlasLayoutFiles.FILE_NAME);
        if (!Files.isRegularFile(pngFile) || !Files.isRegularFile(layoutFile)) {
            return Optional.empty();
        }
        try {
            AtlasLayout layout = AtlasLayoutFiles.read(layoutFile);
            return Optional.of(new AtlasReuse(layout, Files.readAllBytes(pngFile)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取已发布图集失败: " + mapDir, e);
        }
    }

    @Override
    public void save(String mapId, AtlasResult atlas, byte[] png) {
        Path mapDir = publishRoot.resolve(mapId);
        try {
            Files.createDirectories(mapDir);
            Files.write(mapDir.resolve("atlas.png"), png);
            AtlasLayoutFiles.write(mapDir.resolve(AtlasLayoutFiles.FILE_NAME), atlas.layout());
        } catch (IOException e) {
            throw new UncheckedIOException("写已发布图集失败: " + mapDir, e);
        }
    }
}
