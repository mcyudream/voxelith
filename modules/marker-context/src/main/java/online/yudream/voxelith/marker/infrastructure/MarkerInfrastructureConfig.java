package online.yudream.voxelith.marker.infrastructure;

import online.yudream.voxelith.marker.application.MarkerCodecPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 标注上下文的基础设施装配：协议编解码实现。
 *
 * <p>放在 infrastructure（而不是 interfaces 的 Controller 里）：interfaces 层不许依赖
 * infrastructure，由这里把 {@link MarkerCodecPort} 的实现注册成 Bean，
 * Controller 只按 application 的接口注入。仓储由 {@link FileMarkerRepository} 上的
 * {@code @Repository} 自带装配。</p>
 */
@Configuration
public class MarkerInfrastructureConfig {

    @Bean
    MarkerCodecPort markerCodecPort() {
        return new JsonMarkerCodec();
    }
}
