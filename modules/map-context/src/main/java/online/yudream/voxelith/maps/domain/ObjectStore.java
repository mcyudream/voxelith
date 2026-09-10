package online.yudream.voxelith.maps.domain;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 发布对象存储端口（FILE 一期实现、S3 SPI 预留）。
 * 键为相对发布根的路径（如 {@code demo/manifest.json}、{@code demo/tiles/hires/0/0.glb}），
 * 与静态资源 URL {@code /maps/{key}} 对齐。
 *
 * <p>实现不得出现在 domain；S3 适配器走 HTTP 签名 PUT/GET，不强制引入 AWS SDK。
 */
public interface ObjectStore {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);

    boolean exists(String key);

    void delete(String key);

    /** 列出给定前缀下的对象键（不含目录伪对象）。 */
    List<String> list(String prefix);

    default InputStream open(String key) {
        return get(key).map(java.io.ByteArrayInputStream::new)
                .orElseThrow(() -> new IllegalArgumentException("对象不存在: " + key));
    }
}
