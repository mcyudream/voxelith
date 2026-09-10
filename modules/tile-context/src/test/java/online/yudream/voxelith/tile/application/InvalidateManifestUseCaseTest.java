package online.yudream.voxelith.tile.application;

import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.infrastructure.artifact.FileManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidateManifestUseCaseTest {

    @TempDir
    Path publishRoot;

    @Test
    void replacesSha1AndDropsDeletedTilesThenRecalculatesVersion() {
        FileManifestStore store = new FileManifestStore(publishRoot);
        MapManifest original = new MapManifest(1, "demo", "演示", "aaaaaaaaaaaa", "t0",
                new MapManifest.Settings(32, 1),
                new float[]{0, 0, 0}, new float[]{32, 64, 32},
                new MapManifest.AtlasRef("atlas.png", 256, 4),
                List.of(
                        tile("tiles/hires/0/0.glb", "sha-keep"),
                        tile("tiles/hires/0/1.glb", "sha-old"),
                        tile("tiles/hires/1/0.glb", "sha-gone")));
        store.save("demo", original);

        MapManifest updated = new InvalidateManifestUseCase(store)
                .invalidate("demo", Map.of(
                        "tiles/hires/0/1.glb", "sha-new",
                        "tiles/hires/1/0.glb", ""));

        assertThat(updated.tiles()).hasSize(2);
        assertThat(updated.tiles().get(0).sha1()).isEqualTo("sha-keep");
        assertThat(updated.tiles().get(1).sha1()).isEqualTo("sha-new");
        assertThat(updated.version()).isNotEqualTo(original.version());
        assertThat(updated.version()).isEqualTo(InvalidateManifestUseCase.contentVersion(updated.tiles()));
        assertThat(updated.atlas().url()).isEqualTo("atlas.png");
        assertThat(updated.settings().hiresTileSize()).isEqualTo(32);

        MapManifest reloaded = store.load("demo").orElseThrow();
        assertThat(reloaded.version()).isEqualTo(updated.version());
        assertThat(reloaded.tiles()).extracting(MapManifest.TileEntry::url)
                .containsExactly("tiles/hires/0/0.glb", "tiles/hires/0/1.glb");
    }

    private static MapManifest.TileEntry tile(String url, String sha1) {
        return new MapManifest.TileEntry(0, 0, 0, url, sha1, 10, 1,
                new float[]{0, 0, 0}, new float[]{1, 1, 1});
    }
}
