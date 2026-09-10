package online.yudream.voxelith.lod.infrastructure.heightfield;

import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.lod.domain.heightfield.LodSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileHeightfieldStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsBinaryHeightfield() {
        Heightfield field = Heightfield.fromSamples(List.of(
                new LodSample(0.5f, 64, 0.5f, 0x112233),
                new LodSample(2.5f, 70, 2.5f, 0xAABBCC)), 2);
        FileHeightfieldStore store = new FileHeightfieldStore(dir.resolve("heightfield.bin"));
        store.save(field);
        Heightfield loaded = store.load().orElseThrow();
        assertThat(loaded.footprint()).isEqualTo(2);
        assertThat(loaded.topY(0, 0)).isEqualTo(64f);
        assertThat(loaded.topY(1, 1)).isEqualTo(70f);
        assertThat(loaded.rgb(1, 1)).isEqualTo(0xAABBCC);
    }

    @Test
    void missingFileYieldsEmpty() {
        assertThat(new FileHeightfieldStore(dir.resolve("none.bin")).load()).isEmpty();
    }
}
