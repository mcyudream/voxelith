package online.yudream.voxelith.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 瓦片静态服务契约：manifest no-cache、glb 强缓存、按发布目录寻址。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TileStaticServingTest {

    private static final Path PUBLISH_DIR = preparePublishDir();

    private static Path preparePublishDir() {
        try {
            Path dir = Files.createTempDirectory("yudream-tiles-test");
            Files.createDirectories(dir.resolve("demo/tiles/hires/0"));
            Files.writeString(dir.resolve("demo/manifest.json"), "{\"mapId\":\"demo\"}");
            Files.write(dir.resolve("demo/tiles/hires/0/0.glb"), new byte[]{0x67, 0x6C, 0x54, 0x46});
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("yudream.voxelith.publish-dir", PUBLISH_DIR::toString);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesManifestWithNoCache() {
        ResponseEntity<String> response = restTemplate.getForEntity("/maps/demo/manifest.json", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"demo\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
    }

    @Test
    void servesTileGlbWithStrongCache() {
        ResponseEntity<byte[]> response = restTemplate.getForEntity("/maps/demo/tiles/hires/0/0.glb", byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith(new byte[]{0x67, 0x6C, 0x54, 0x46});
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("max-age");
    }

    @Test
    void missingMapReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/maps/nope/manifest.json", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
