package online.yudream.voxelith.orchestration.infrastructure.watch;

import online.yudream.voxelith.orchestration.domain.RegionObjectSource;
import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对象存储版的 region 监听：**轮询 + 版本比对**，等价于本地磁盘上的 WatchService。
 *
 * <p>为什么需要它：{@code WatchServiceRegionWatch} 监听的是本地目录的 inotify 事件，
 * 而存档放在 S3/MinIO/R2 上时没有任何事件可监听。对象存储能给的只有「列出 + ETag」，
 * 所以变更检测退化为：定期列一次，比对每个对象的版本串（ETag / size-mtime），
 * 把新增、内容变化、删除翻译成 {@link RegionPos} 通知。</p>
 *
 * <p>可选**镜像**：增量重渲染读的是本地 Anvil 文件，所以变更的对象要落到本地
 * {@code region/} 目录（原子替换），被删除的对象要删掉本地副本。不配镜像目录时就只发通知，
 * 由调用方自己准备本地文件。</p>
 *
 * <p>语义边界（写下来免得被误解）：首次轮询只建立基线、不发通知（否则重启会把整张图
 * 全部当成变更重跑一遍）；停机期间发生的变更不会补发——那是调度/同步层的职责。</p>
 */
public final class PollingRegionWatch implements RegionWatchPort {

    /** 只有 {@code r.X.Z.mca} 才算 region；其它对象（level.dat、备份等）忽略。 */
    private static final String REGION_SUFFIX = ".mca";

    private final RegionObjectSource source;
    private final String prefix;
    /** 本地镜像目录；null = 只发通知不落盘。 */
    private final Path mirrorDir;
    private final Duration interval;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    /** 上一轮的 key → version。 */
    private Map<String, String> snapshot = Map.of();

    public PollingRegionWatch(RegionObjectSource source, String prefix, Path mirrorDir,
                              Duration interval) {
        this.source = source;
        this.prefix = prefix;
        this.mirrorDir = mirrorDir;
        this.interval = interval;
    }

    @Override
    public void start(RegionChangeListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("region 轮询已启动");
        }
        snapshot = currentVersions();
        thread = new Thread(() -> loop(listener), "voxelith-region-poll");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running.set(false);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }

    /** 供测试与「立即对一次账」使用：比对一次并通知变更，返回变更的 region。 */
    public List<RegionPos> pollOnce(RegionChangeListener listener) {
        Map<String, String> previous = snapshot;
        Map<String, String> current = currentVersions();
        snapshot = current;

        Map<String, RegionPos> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().equals(previous.get(key))) {
                mirror(key);
                regionOf(key).ifPresent(region -> changed.putIfAbsent(key, region));
            }
        }
        for (String key : previous.keySet()) {
            if (!current.containsKey(key)) {
                removeMirror(key);
                regionOf(key).ifPresent(region -> changed.putIfAbsent(key, region));
            }
        }
        for (RegionPos region : changed.values()) {
            listener.onRegionChanged(region);
        }
        return List.copyOf(changed.values());
    }

    private void loop(RegionChangeListener listener) {
        while (running.get()) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                pollOnce(listener);
            } catch (RuntimeException e) {
                // 轮询失败（网络抖动）不该杀掉监听线程：下一轮重试
                System.err.println("[voxelith] region 轮询失败，下一轮重试: " + e);
            }
        }
    }

    private Map<String, String> currentVersions() {
        Map<String, String> versions = new HashMap<>();
        for (RegionObjectSource.RegionObject object : source.list(prefix)) {
            if (object.key().endsWith(REGION_SUFFIX) && regionOf(object.key()).isPresent()) {
                versions.put(object.key(), object.version());
            }
        }
        return versions;
    }

    /** 把对象拉到本地镜像目录（原子替换），保证增量渲染读到的是新内容。 */
    private void mirror(String key) {
        if (mirrorDir == null) {
            return;
        }
        Optional<byte[]> bytes = source.get(key);
        if (bytes.isEmpty()) {
            return;
        }
        Path target = mirrorFile(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes.get());
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("镜像 region 失败: " + key, e);
        }
    }

    private void removeMirror(String key) {
        if (mirrorDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(mirrorFile(key));
        } catch (IOException e) {
            throw new UncheckedIOException("删除本地 region 镜像失败: " + key, e);
        }
    }

    private Path mirrorFile(String key) {
        String relative = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
        return mirrorDir.resolve(relative.replaceFirst("^/", ""));
    }

    /** 从对象键里取出 region 坐标（取最后一段文件名）。 */
    private static Optional<RegionPos> regionOf(String key) {
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        return RegionPos.parseFileName(name);
    }
}
