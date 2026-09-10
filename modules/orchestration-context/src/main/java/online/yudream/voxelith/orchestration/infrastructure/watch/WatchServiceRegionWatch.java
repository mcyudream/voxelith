package online.yudream.voxelith.orchestration.infrastructure.watch;

import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.ClosedWatchServiceException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link WatchService} 实现：监听存档 {@code region/} 目录下 {@code r.X.Z.mca} 的
 * CREATE/MODIFY/DELETE，解析为 {@link RegionPos} 投递给监听器。
 * 同一文件的连发事件由上层 {@code IncrementalUpdateUseCase} 防抖合并。
 */
public final class WatchServiceRegionWatch implements RegionWatchPort {

    private final Path regionDir;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile WatchService watchService;
    private volatile Thread thread;

    public WatchServiceRegionWatch(Path regionDir) {
        this.regionDir = regionDir;
    }

    @Override
    public void start(RegionChangeListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("region 监听已启动: " + regionDir);
        }
        try {
            Files.createDirectories(regionDir);
            watchService = FileSystems.getDefault().newWatchService();
            regionDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            running.set(false);
            throw new UncheckedIOException("无法监听 region 目录: " + regionDir, e);
        }
        thread = new Thread(() -> loop(listener), "voxelith-region-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop(RegionChangeListener listener) {
        WatchService service = watchService;
        while (running.get() && service != null) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException ignored) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                Path name = (Path) event.context();
                if (name == null) {
                    continue;
                }
                RegionPos.parseFileName(name.toString()).ifPresent(listener::onRegionChanged);
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // 关闭即停
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
