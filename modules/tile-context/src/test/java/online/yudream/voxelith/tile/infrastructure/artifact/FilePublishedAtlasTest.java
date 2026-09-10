package online.yudream.voxelith.tile.infrastructure.artifact;

import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.domain.atlas.AtlasPacker;
import online.yudream.voxelith.tile.domain.atlas.AtlasTexture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilePublishedAtlasTest {

    @TempDir
    Path publishRoot;

    @Test
    void roundTripsLayoutAndPng() {
        int[] argb = new int[256];
        java.util.Arrays.fill(argb, 0xFF112233);
        AtlasTexture tex = new AtlasTexture(new Identifier("minecraft", "block/stone"), 16, 16, argb);
        var packed = new AtlasPacker().pack(List.of(tex));
        byte[] png = new byte[]{1, 2, 3, 4};

        FilePublishedAtlas store = new FilePublishedAtlas(publishRoot);
        store.save("demo", packed, png);

        assertThat(Files.isRegularFile(publishRoot.resolve("demo/atlas.png"))).isTrue();
        assertThat(Files.isRegularFile(publishRoot.resolve("demo/atlas-layout.json"))).isTrue();

        AtlasReuse loaded = store.load("demo").orElseThrow();
        assertThat(loaded.png()).isEqualTo(png);
        assertThat(loaded.layout().pixelSize()).isEqualTo(packed.layout().pixelSize());
        assertThat(loaded.layout().cellIndex()).containsKey("minecraft:block/stone");
    }

    @Test
    void missingFilesYieldEmpty() {
        assertThat(new FilePublishedAtlas(publishRoot).load("nope")).isEmpty();
    }
}
