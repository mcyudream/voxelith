package online.yudream.voxelith.orchestration.infrastructure.watch;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WatchServiceRegionWatchTest {

    @TempDir
    Path dir;

    @Test
    void notifiesOnMcaCreateAndIgnoresOtherFiles() throws Exception {
        Path regionDir = dir.resolve("region");
        Files.createDirectories(regionDir);
        Set<RegionPos> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(1);
        try (WatchServiceRegionWatch watch = new WatchServiceRegionWatch(regionDir)) {
            watch.start(region -> {
                seen.add(region);
                latch.countDown();
            });
            // WatchService 在部分平台上对注册后立刻写入可能丢失，给注册留一点时间
            Thread.sleep(80);
            Files.write(regionDir.resolve("r.3.-1.mca"), new byte[]{1, 2, 3});
            Files.write(regionDir.resolve("session.lock"), new byte[]{9});
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(seen).containsExactly(new RegionPos(3, -1));
    }

    @Test
    void parseFileNameAcceptsNegativeCoordinates() {
        assertThat(RegionPos.parseFileName("r.-12.4.mca")).contains(new RegionPos(-12, 4));
        assertThat(RegionPos.parseFileName("r.0.0.mca")).contains(new RegionPos(0, 0));
        assertThat(RegionPos.parseFileName("session.lock")).isEmpty();
        assertThat(RegionPos.parseFileName("r.0.0.mcc")).isEmpty();
    }
}
