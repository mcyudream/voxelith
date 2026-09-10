package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.IncrementalJob;
import online.yudream.voxelith.orchestration.domain.IncrementalRenderPort;
import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * region 增量更新用例：WatchService 事件经防抖窗口合并后按 region 重渲染 bake→tile→lod，
 * 再局部失效清单哈希（contentVersion 随被更新瓦片 sha1 变化）。
 *
 * <p>冷却期内到达的事件并入下一次调度；同 region 多次修改只进一次作业。
 * 关闭后停止监听并取消未触发的调度。
 */
public class IncrementalUpdateUseCase implements AutoCloseable {

    public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(2);

    private final RegionWatchPort watch;
    private final IncrementalRenderPort render;
    private final ManifestInvalidatePort invalidate;
    private final ScheduledExecutorService scheduler;
    private final Duration debounce;
    private final String mapId;
    private final Set<RegionPos> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> scheduled;

    public IncrementalUpdateUseCase(RegionWatchPort watch, IncrementalRenderPort render,
                                    ManifestInvalidatePort invalidate,
                                    ScheduledExecutorService scheduler,
                                    Duration debounce, String mapId) {
        this.watch = watch;
        this.render = render;
        this.invalidate = invalidate;
        this.scheduler = scheduler;
        this.debounce = debounce;
        this.mapId = mapId;
    }

    public void start() {
        watch.start(this::onRegionChanged);
    }

    /** 供测试与 WatchService 回调共用：入队并重置防抖计时。 */
    public void onRegionChanged(RegionPos region) {
        if (closed.get()) {
            return;
        }
        pending.add(region);
        reschedule();
    }

    private synchronized void reschedule() {
        if (closed.get()) {
            return;
        }
        ScheduledFuture<?> previous = scheduled;
        if (previous != null) {
            previous.cancel(false);
        }
        scheduled = scheduler.schedule(this::flush, debounce.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void flush() {
        if (closed.get()) {
            return;
        }
        List<RegionPos> regions = drain();
        if (regions.isEmpty()) {
            return;
        }
        IncrementalJob job = new IncrementalJob(mapId, regions, System.currentTimeMillis());
        try {
            Map<String, String> sha1ByUrl = render.rerender(job);
            invalidate.invalidate(mapId, sha1ByUrl);
        } catch (Exception e) {
            throw new IllegalStateException("增量重渲染失败: " + job.regions(), e);
        }
    }

    private List<RegionPos> drain() {
        List<RegionPos> regions = new ArrayList<>();
        for (RegionPos region : pending) {
            if (pending.remove(region)) {
                regions.add(region);
            }
        }
        return List.copyOf(regions);
    }

    /** 测试用：当前防抖窗口内尚未 flush 的 region。 */
    public Set<RegionPos> pendingRegions() {
        return Set.copyOf(pending);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> previous = scheduled;
        if (previous != null) {
            previous.cancel(false);
        }
        watch.close();
    }
}
