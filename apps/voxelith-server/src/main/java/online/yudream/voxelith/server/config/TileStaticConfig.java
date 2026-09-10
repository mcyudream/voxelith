package online.yudream.voxelith.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 静态瓦片服务：{publishDir}/{mapId}/ 下的 manifest.json / atlas.png / tiles/**。
 *
 * <p>瓦片与图集按内容哈希寻址（manifest 记录 sha1），走强缓存；manifest 每次重渲染都变，走 no-cache。</p>
 */
@Configuration
public class TileStaticConfig implements WebMvcConfigurer {

    private final String publishLocation;

    public TileStaticConfig(@Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        this.publishLocation = Path.of(publishDir).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/maps/*/manifest.json")
                .addResourceLocations(publishLocation)
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/maps/*/tiles/**", "/maps/*/atlas.png")
                .addResourceLocations(publishLocation)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
