package online.yudream.voxelith.server.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务表上界：完成的任务按 FIFO 淘汰，**运行中的一个都不能被淘汰**。
 *
 * <p>回归背景：此前 RenderJobService 只 put 不 remove，长跑进程里任务记录（含每任务最多
 * 2000 行日志）会无限增长。</p>
 */
class BoundedJobRegistryTest {

    @Test
    @DisplayName("完成的任务只保留最近 N 个")
    void evictsOldestFinished() {
        BoundedJobRegistry<String> registry = new BoundedJobRegistry<>(3);
        for (int i = 0; i < 10; i++) {
            registry.put("job-" + i, "payload-" + i);
            registry.markFinished("job-" + i);
        }
        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.retainedFinishedCount()).isEqualTo(3);
        // 保留的是最近三个
        assertThat(registry.get("job-9")).isPresent();
        assertThat(registry.get("job-8")).isPresent();
        assertThat(registry.get("job-7")).isPresent();
        assertThat(registry.get("job-0")).isEmpty();
    }

    @Test
    @DisplayName("运行中的任务不会被淘汰：先完成再持续提交，运行中的记录一直在")
    void keepsRunningJobs() {
        BoundedJobRegistry<String> registry = new BoundedJobRegistry<>(2);
        registry.put("running", "still-rendering");
        for (int i = 0; i < 5; i++) {
            registry.put("done-" + i, "payload");
            registry.markFinished("done-" + i);
        }
        assertThat(registry.get("running")).isPresent();
        assertThat(registry.size()).isEqualTo(3);   // running + 最近两个完成的
        assertThat(registry.list()).contains("still-rendering");
    }

    @Test
    @DisplayName("markFinished 幂等，且注册表里没有的 id 不会凭空占用名额")
    void markFinishedIsIdempotent() {
        BoundedJobRegistry<String> registry = new BoundedJobRegistry<>(2);
        registry.put("a", "job-a");
        registry.markFinished("a");
        registry.markFinished("a");
        registry.markFinished("missing");
        assertThat(registry.retainedFinishedCount()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发 put/markFinished 不丢结构（多线程下仍能淘汰到上界）")
    void threadSafe() throws Exception {
        BoundedJobRegistry<Integer> registry = new BoundedJobRegistry<>(8);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int base = t * 100;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    registry.put("j" + (base + i), base + i);
                    registry.markFinished("j" + (base + i));
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(registry.size()).isLessThanOrEqualTo(8);
        assertThat(registry.retainedFinishedCount()).isLessThanOrEqualTo(9); // 淘汰有 1 的宽松余量
    }
}
