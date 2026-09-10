package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.manifest.MapManifest;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 清单局部失效：按 url 替换/删除指定瓦片条目，重算 contentVersion 后原子写回。
 * 未被点名的条目（含包围盒、图集、settings）保持原值，前端缓存只对变化瓦片失效。
 */
public class InvalidateManifestUseCase {

    private final ManifestStore store;

    public InvalidateManifestUseCase(ManifestStore store) {
        this.store = store;
    }

    public MapManifest invalidate(String mapId, Map<String, String> sha1ByUrl) {
        MapManifest current = store.load(mapId)
                .orElseThrow(() -> new IllegalStateException("清单不存在: " + mapId));
        List<MapManifest.TileEntry> next = new ArrayList<>();
        for (MapManifest.TileEntry tile : current.tiles()) {
            if (!sha1ByUrl.containsKey(tile.url())) {
                next.add(tile);
                continue;
            }
            String sha1 = sha1ByUrl.get(tile.url());
            if (sha1 == null || sha1.isEmpty()) {
                continue;
            }
            next.add(new MapManifest.TileEntry(
                    tile.level(), tile.x(), tile.z(), tile.url(),
                    sha1, tile.bytes(), tile.quads(), tile.min(), tile.max()));
        }
        MapManifest updated = new MapManifest(
                current.formatVersion(), current.mapId(), current.name(),
                contentVersion(next), Instant.now().toString(),
                current.settings(), current.boundsMin(), current.boundsMax(),
                current.atlas(), List.copyOf(next));
        store.save(mapId, updated);
        return updated;
    }

    static String contentVersion(List<MapManifest.TileEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (MapManifest.TileEntry entry : entries) {
                digest.update(entry.sha1().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
