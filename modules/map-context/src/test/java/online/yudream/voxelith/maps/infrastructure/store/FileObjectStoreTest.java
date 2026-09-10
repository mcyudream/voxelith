package online.yudream.voxelith.maps.infrastructure.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileObjectStoreTest {

    @TempDir
    Path root;

    @Test
    void putGetListDeleteRoundTrip() {
        FileObjectStore store = new FileObjectStore(root);
        store.put("demo/manifest.json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "application/json");
        store.put("demo/tiles/hires/0/0.glb", new byte[]{1, 2, 3}, "model/gltf-binary");

        assertThat(store.exists("demo/manifest.json")).isTrue();
        assertThat(store.get("demo/manifest.json")).isPresent();
        assertThat(new String(store.get("demo/manifest.json").orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"ok\":true}");
        assertThat(store.list("demo/")).containsExactly(
                "demo/manifest.json", "demo/tiles/hires/0/0.glb");
        assertThat(store.list("demo/tiles/")).containsExactly("demo/tiles/hires/0/0.glb");

        store.delete("demo/manifest.json");
        assertThat(store.exists("demo/manifest.json")).isFalse();
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void rejectsPathTraversal() {
        FileObjectStore store = new FileObjectStore(root);
        assertThatThrownBy(() -> store.put("../escape.txt", new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
        assertThat(Files.exists(root.resolve("../escape.txt").normalize())).isFalse();
    }
}
