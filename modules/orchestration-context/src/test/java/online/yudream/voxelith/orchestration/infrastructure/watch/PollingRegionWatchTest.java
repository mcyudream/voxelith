package online.yudream.voxelith.orchestration.infrastructure.watch;

import online.yudream.voxelith.orchestration.domain.RegionObjectSource;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象存储版 region 监听：轮询 + 版本比对，等价于本地 WatchService。
 *
 * <p>用内存对象源模拟 S3（接口一致，只是 list/get 换了个实现），
 * 覆盖「新增 / 内容变化 / 删除 / 无关对象不动」四种情况。</p>
 */
class PollingRegionWatchTest {

    /** 内存对象源：key → (version, bytes)。 */
    private static final class MemorySource implements RegionObjectSource {
        final Map<String, String> versions = new LinkedHashMap<>();
        final Map<String, byte[]> contents = new LinkedHashMap<>();

        void put(String key, String version, String body) {
            versions.put(key, version);
            contents.put(key, body.getBytes(StandardCharsets.UTF_8));
        }

        void remove(String key) {
            versions.remove(key);
            contents.remove(key);
        }

        @Override
        public List<RegionObject> list(String prefix) {
            List<RegionObject> objects = new ArrayList<>();
            versions.forEach((key, version) -> {
                if (key.startsWith(prefix)) {
                    objects.add(new RegionObject(key, version, contents.get(key).length, 0));
                }
            });
            return objects;
        }

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(contents.get(key));
        }
    }

    @Test
    @DisplayName("首次轮询只建基线；之后按版本变化发通知并镜像到本地 region 目录")
    void detectsChangesAndMirrors(@TempDir Path mirrorDir) throws Exception {
        MemorySource source = new MemorySource();
        source.put("region/r.0.0.mca", "etag-1", "chunk-a");
        source.put("region/level.dat", "etag-l", "level");

        PollingRegionWatch watch = new PollingRegionWatch(source, "region/", mirrorDir,
                Duration.ofMillis(50));
        List<RegionPos> changes = new CopyOnWriteArrayList<>();
        watch.start(changes::add);

        // 首次列表只建立基线：不发通知、不下载
        assertThat(changes).isEmpty();
        assertThat(Files.exists(mirrorDir.resolve("r.0.0.mca"))).isFalse();

        // 新增一片 region + 已有一片内容变化 + 一个无关对象变化
        source.put("region/r.0.1.mca", "etag-2", "chunk-b");
        source.put("region/r.0.0.mca", "etag-1b", "chunk-a2");
        source.put("region/level.dat", "etag-l2", "level2");
        List<RegionPos> firstRound = watch.pollOnce(changes::add);

        assertThat(firstRound).containsExactlyInAnyOrder(new RegionPos(0, 1), new RegionPos(0, 0));
        assertThat(changes).containsExactlyInAnyOrder(new RegionPos(0, 1), new RegionPos(0, 0));
        assertThat(Files.readString(mirrorDir.resolve("r.0.0.mca"))).isEqualTo("chunk-a2");
        assertThat(Files.readString(mirrorDir.resolve("r.0.1.mca"))).isEqualTo("chunk-b");
        // level.dat 不是 region：即使变了也不通知、不镜像
        assertThat(Files.exists(mirrorDir.resolve("level.dat"))).isFalse();

        // 没有变化的一轮：不发通知
        assertThat(watch.pollOnce(changes::add)).isEmpty();
        assertThat(changes).hasSize(2);

        // 删除：本地镜像跟着删，并通知
        source.remove("region/r.0.1.mca");
        assertThat(watch.pollOnce(changes::add)).containsExactly(new RegionPos(0, 1));
        assertThat(Files.exists(mirrorDir.resolve("r.0.1.mca"))).isFalse();
        assertThat(Files.exists(mirrorDir.resolve("r.0.0.mca"))).isTrue();

        watch.close();
    }

    @Test
    @DisplayName("不配镜像目录时只发通知（由调用方自己准备本地文件）")
    void notifyOnlyMode() {
        MemorySource source = new MemorySource();
        source.put("region/r.-2.3.mca", "etag-1", "x");
        PollingRegionWatch watch = new PollingRegionWatch(source, "region/", null,
                Duration.ofSeconds(30));

        List<RegionPos> changes = new ArrayList<>();
        watch.start(changes::add);
        source.put("region/r.-2.3.mca", "etag-2", "y");
        assertThat(watch.pollOnce(changes::add)).containsExactly(new RegionPos(-2, 3));
        assertThat(changes).containsExactly(new RegionPos(-2, 3));
        watch.close();
    }

    @Test
    @DisplayName("后台轮询线程按间隔工作；close 后停止")
    void backgroundLoopStopsOnClose() throws Exception {
        MemorySource source = new MemorySource();
        source.put("region/r.0.0.mca", "etag-1", "a");
        PollingRegionWatch watch = new PollingRegionWatch(source, "region/", null,
                Duration.ofMillis(30));
        List<RegionPos> changes = new CopyOnWriteArrayList<>();
        watch.start(changes::add);

        source.put("region/r.9.9.mca", "etag-9", "b");
        long deadline = System.currentTimeMillis() + 5_000;
        while (changes.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(changes).contains(new RegionPos(9, 9));

        watch.close();
        int seen = changes.size();
        source.put("region/r.8.8.mca", "etag-8", "c");
        Thread.sleep(200);
        assertThat(changes).hasSize(seen);
    }
}
