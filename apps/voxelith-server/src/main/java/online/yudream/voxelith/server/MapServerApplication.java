package online.yudream.voxelith.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * voxelith 后端启动入口（薄启动层）。
 * 扫描 online.yudream.voxelith 全包，由各限界上下文的 interfaces/infrastructure 层自行装配。
 */
@SpringBootApplication(scanBasePackages = "online.yudream.voxelith")
public class MapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapServerApplication.class, args);
    }
}
