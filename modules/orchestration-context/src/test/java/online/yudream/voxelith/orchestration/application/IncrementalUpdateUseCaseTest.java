package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.IncrementalJob;
import online.yudream.voxelith.orchestration.domain.IncrementalPatch;
import online.yudream.voxelith.orchestration.domain.IncrementalRenderPort;
import online.yudream.voxelith.orchestration.domain.ManifestInvalidatePort;
import online.yudream.voxelith.orchestration.domain.RegionWatchPort;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalUpdateUseCaseTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private IncrementalUpdateUseCase useCase;

    @AfterEach
    void tearDown() {
        if (useCase != null) {
            useCase.close();
        }
        scheduler.shutdownNow();
    }

    @Test
    void debounceMergesDuplicateAndCoalescesRegionsIntoOneJob() throws Exception {
        RecordingRender render = new RecordingRender();
        RecordingInvalidate invalidate = new RecordingInvalidate();
        useCase = new IncrementalUpdateUseCase(
                new NoopWatch(), render, invalidate, scheduler,
                Duration.ofMillis(80), "demo");
        useCase.start();

        useCase.onRegionChanged(new RegionPos(0, 0));
        useCase.onRegionChanged(new RegionPos(0, 0));
        useCase.onRegionChanged(new RegionPos(1, 0));
        assertThat(useCase.pendingRegions()).hasSize(2);

        assertThat(render.await(1, 2, TimeUnit.SECONDS)).isTrue();
        assertThat(render.jobs).hasSize(1);
        assertThat(render.jobs.getFirst().regions())
                .containsExactlyInAnyOrder(new RegionPos(0, 0), new RegionPos(1, 0));
        assertThat(invalidate.calls).containsExactly(
                Map.entry("demo", Map.of("tiles/hires/0/0.glb", "abc")));
        assertThat(useCase.pendingRegions()).isEmpty();
    }

    @Test
    void eventsDuringCooldownJoinNextFlush() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch holdFirst = new CountDownLatch(1);
        List<IncrementalJob> jobs = new CopyOnWriteArrayList<>();
        IncrementalRenderPort slow = job -> {
            jobs.add(job);
            firstStarted.countDown();
            if (jobs.size() == 1) {
                holdFirst.await(2, TimeUnit.SECONDS);
            }
            return IncrementalPatch.empty();
        };
        RecordingInvalidate invalidate = new RecordingInvalidate();
        useCase = new IncrementalUpdateUseCase(
                new NoopWatch(), slow, invalidate, scheduler,
                Duration.ofMillis(40), "demo");
        useCase.start();

        useCase.onRegionChanged(new RegionPos(0, 0));
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        // 第一次 flush 进行中再来事件：入下一次窗口
        useCase.onRegionChanged(new RegionPos(2, 2));
        holdFirst.countDown();

        // 等第二次 flush
        long deadline = System.currentTimeMillis() + 2000;
        while (jobs.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).regions()).containsExactly(new RegionPos(0, 0));
        assertThat(jobs.get(1).regions()).containsExactly(new RegionPos(2, 2));
    }

    @Test
    void closeCancelsPendingFlush() throws Exception {
        AtomicInteger renders = new AtomicInteger();
        useCase = new IncrementalUpdateUseCase(
                new NoopWatch(),
                job -> {
                    renders.incrementAndGet();
                    return IncrementalPatch.empty();
                },
                (mapId, patch) -> { },
                scheduler, Duration.ofMillis(200), "demo");
        useCase.start();
        useCase.onRegionChanged(new RegionPos(0, 0));
        useCase.close();
        Thread.sleep(300);
        assertThat(renders.get()).isZero();
    }

    private static final class NoopWatch implements RegionWatchPort {
        @Override
        public void start(RegionChangeListener listener) {
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingRender implements IncrementalRenderPort {
        final List<IncrementalJob> jobs = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public IncrementalPatch rerender(IncrementalJob job) {
            jobs.add(job);
            latch.countDown();
            return new IncrementalPatch(Map.of("tiles/hires/0/0.glb", "abc"), List.of());
        }

        boolean await(int n, long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit) && jobs.size() >= n;
        }
    }

    private static final class RecordingInvalidate implements ManifestInvalidatePort {
        final List<Map.Entry<String, Map<String, String>>> calls = new CopyOnWriteArrayList<>();

        @Override
        public void invalidate(String mapId, IncrementalPatch patch) {
            calls.add(Map.entry(mapId, Map.copyOf(patch.sha1ByUrl())));
        }
    }
}
