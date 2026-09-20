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

    /**
     * 列出给定前缀下的对象及其元数据（键 + 字节数 + 最后修改时间 + 版本标识）。
     *
     * <p>用途是「没有 WatchService 的对象存储上做变更检测」：S3 侧没有 inotify，
     * 只能定期列出并比对 ETag/大小/修改时间。默认实现退化为只用键（无法检测内容变化），
     * 真正支持元数据的实现应覆写它。</p>
     */
    default List<ObjectMeta> listMeta(String prefix) {
        return list(prefix).stream()
                .map(key -> new ObjectMeta(key, -1, 0, ""))
                .toList();
    }

    /**
     * 对象元数据。
     *
     * @param key                 对象键
     * @param size                字节数（-1 = 未知）
     * @param lastModifiedEpochMs 最后修改时间（0 = 未知）
     * @param version             版本标识：S3 的 ETag、本地文件的 size-mtime 派生串；
     *                            内容变了它一定变，是变更检测的主判据
     */
    record ObjectMeta(String key, long size, long lastModifiedEpochMs, String version) {
    }

    default InputStream open(String key) {
        return get(key).map(java.io.ByteArrayInputStream::new)
                .orElseThrow(() -> new IllegalArgumentException("对象不存在: " + key));
    }
}
