package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.sharedkernel.vo.TilePos;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.domain.tile.TileMeshAssembler;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * manifest 链路用例：由 tile 产物汇总清单并发布到发布根（原子替换，供前端与后端瓦片服务消费）。
 */
public class PublishManifestUseCase {

    private final ManifestPublisher publisher;

    public PublishManifestUseCase(ManifestPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * @param mapId       地图 id
     * @param name        展示名
     * @param tilesDir    tile 链路产物目录
     * @param outcome     tile 链路结果（瓦片摘要 + 图集信息）
     * @param publishRoot 发布根目录
     */
    public MapManifest publish(String mapId, String name, Path tilesDir, TileOutcome outcome,
                               Path publishRoot) {
        List<MapManifest.TileEntry> entries = new ArrayList<>();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int maxLevel = 0;
        for (TileOutcome.TileSummary tile : outcome.tiles()) {
            TilePos pos = tile.pos();
            String url = pos.isHires()
                    ? "tiles/hires/" + pos.x() + "/" + pos.z() + ".glb"
                    : "tiles/lod/" + pos.level() + "/" + pos.x() + "/" + pos.z() + ".glb";
            entries.add(new MapManifest.TileEntry(pos.level(), pos.x(), pos.z(),
                    url, tile.sha1(), tile.bytes(), tile.quads(), tile.min(), tile.max()));
            maxLevel = Math.max(maxLevel, pos.level());
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], tile.min()[i]);
                max[i] = Math.max(max[i], tile.max()[i]);
            }
        }
        if (entries.isEmpty()) {
            min = new float[]{0, 0, 0};
            max = new float[]{0, 0, 0};
        }

        MapManifest manifest = new MapManifest(1, mapId, name, contentVersion(entries),
                Instant.now().toString(),
                new MapManifest.Settings(TileMeshAssembler.HIRES_TILE_SIZE, maxLevel + 1),
                min, max,
                new MapManifest.AtlasRef("atlas.png", outcome.atlasSize(), outcome.textureCount()),
                entries);

        publisher.publish(mapId, tilesDir, manifest, publishRoot);
        return manifest;
    }

    /** 内容版本 = 全部瓦片 sha1 串联后再取 sha1（任一瓦片变化则版本变化，前端缓存整体失效）。 */
    private static String contentVersion(List<MapManifest.TileEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (MapManifest.TileEntry entry : entries) {
                digest.update(entry.sha1().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
