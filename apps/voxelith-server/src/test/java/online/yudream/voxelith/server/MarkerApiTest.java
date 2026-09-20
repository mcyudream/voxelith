package online.yudream.voxelith.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标注接口契约：PUT 整表写回 → 落盘 markers.json → GET 读回；非法请求体回 400。
 *
 * <p>前端既从 {@code /maps/{mapId}/markers.json} 静态读，也走 {@code /api/maps/{mapId}/markers}
 * 写回，两条路径必须在同一份文件上对齐。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarkerApiTest {

    private static final Path PUBLISH_DIR = preparePublishDir();

    private static Path preparePublishDir() {
        try {
            Path dir = Files.createTempDirectory("yudream-markers-test");
            Files.createDirectories(dir.resolve("demo"));
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
    @DisplayName("写入 → 落盘 → 读回；删除接口清空")
    void roundTripThroughRestAndStaticFile() throws IOException {
        String body = """
                {
                  "formatVersion": 1,
                  "mapId": "demo",
                  "sets": [
                    { "id": "landmarks", "label": "地标", "toggleable": true, "defaultHidden": false,
                      "sorting": 5,
                      "markers": [
                        { "type": "poi", "id": "library", "label": "图书馆",
                          "minDistance": 0, "maxDistance": 1000,
                          "style": { "fillColor": "#e74c3c" },
                          "position": { "x": 12, "y": 70, "z": -8 } }
                      ] }
                  ]
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> put = restTemplate.exchange("/api/maps/demo/markers", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).contains("\"markers\":1");

        // 静态文件与 REST 是同一份数据
        assertThat(Files.readString(PUBLISH_DIR.resolve("demo/markers.json")))
                .contains("\"library\"");
        ResponseEntity<String> get = restTemplate.getForEntity("/api/maps/demo/markers", String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).contains("\"landmarks\"").contains("\"posi");
        assertThat(restTemplate.getForEntity("/maps/demo/markers.json", String.class).getBody())
                .contains("\"library\"");

        ResponseEntity<String> cleared = restTemplate.exchange("/api/maps/demo/markers", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/maps/demo/markers", String.class).getBody())
                .contains("\"sets\": []");
    }

    @Test
    @DisplayName("非法请求体（未知类型 / 坐标缺失）返回 400，不写坏文件")
    void rejectsBadPayload() {
        String bad = """
                {"sets":[{"id":"x","markers":[{"type":"unknown","id":"a","label":"A"}]}]}
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange("/api/maps/demo/markers", HttpMethod.PUT,
                new HttpEntity<>(bad, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("error");
    }
}
