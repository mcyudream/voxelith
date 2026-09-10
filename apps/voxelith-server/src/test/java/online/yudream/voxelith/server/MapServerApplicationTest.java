package online.yudream.voxelith.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MapServerApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void mapsEndpointResponds() {
        String body = restTemplate.getForObject("/api/maps", String.class);
        assertThat(body).isNotNull().startsWith("[");
    }
}
