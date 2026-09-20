package online.yudream.voxelith.orchestration.domain;

import java.util.List;
import java.util.Optional;

/**
 * 存档 region 的**对象存储**视图（S3 / MinIO / R2 之类的来源）。
 *
 * <p>为什么单独定一个端口而不是直接用发布侧的 ObjectStore：跨上下文只允许访问对方的
 * application 层，orchestration 不能依赖 maps.domain 的 ObjectStore。这里按编排域的
 * 需要（列出键 + 版本 + 取内容）重新声明，由组合根（apps）用 ObjectStore 实现它。</p>
 *
 * <p>与 {@link RegionWatchPort} 的关系：本地磁盘用 WatchService 能拿到事件，
 * 对象存储没有 inotify，只能靠 {@link #list} 的版本号（S3 的 ETag）轮询比对——
 * 也就是 {@code PollingRegionWatch}。</p>
 */
public interface RegionObjectSource {

    /**
     * @param key              对象键（如 {@code region/r.0.0.mca}）
     * @param version          版本标识（S3 ETag / 本地 size-mtime）；内容变了它一定变
     * @param size             字节数（-1 = 未知）
     * @param modifiedEpochMs  最后修改时间（0 = 未知）
     */
    record RegionObject(String key, String version, long size, long modifiedEpochMs) {
    }

    /** 列出前缀下的对象。 */
    List<RegionObject> list(String prefix);

    /** 读取对象内容（不存在返回空）。 */
    Optional<byte[]> get(String key);
}
