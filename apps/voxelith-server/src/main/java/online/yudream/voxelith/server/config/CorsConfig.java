package online.yudream.voxelith.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期 CORS 放行（前端 vite dev server → 后端）。
 * 生产部署由反向代理同源托管，此配置不生效。
 *
 * <p>注意：本机 5141–5340 端口段被 Windows 保留（网卡排除范围），vite dev 只能跑在
 * 5173 以外的端口（例如 4173）。TextureLoader 的 crossOrigin 图片请求会带 Origin，
 * 若此处只放行 5173，图集 PNG 会被 CORS 拒绝（403）→ 共享图集永远加载不出 →
 * 整图白模且 hires 瓦片等待图集停摆。因此这里按「localhost 任意端口」放行。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        registry.addMapping("/maps/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "OPTIONS");
    }
}
