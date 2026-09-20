package online.yudream.voxelith.orchestration.infrastructure.shard;

import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardJob;
import online.yudream.voxelith.orchestration.domain.ShardJobState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式分片队列：入队幂等、抢占互斥、租约回收、产物回填。
 *
 * <p>「多机协作」在测试里等价于多个 {@link FileShardQueue} 实例指向同一目录
 * （真实部署就是多进程/多机挂同一个共享目录），所以并发用例刻意用两个实例。</p>
 */
class FileShardQueueTest {

    private static List<String> shards(int count) {
        List<String> shards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add("r." + i + ".0");
        }
        return shards;
    }

    @Test
    @DisplayName("入队幂等：已完成的分片不被重置，租约未过期的分片不抢")
    void enqueueIsIdempotent(@TempDir Path root) {
        FileShardQueue queue = new FileShardQueue(root);
        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0", "r.1.0"));

        ShardJob claimed = queue.claim("worker-a", Duration.ofMinutes(5), PipelineStage.BAKE)
                .orElseThrow();
        queue.complete(PipelineStage.BAKE, claimed.shard(), List.of("bake/" + claimed.shard() + ".ndjson"));

        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0", "r.1.0"));
        assertThat(queue.jobs(PipelineStage.BAKE))
                .filteredOn(job -> job.state() == ShardJobState.DONE)
                .hasSize(1);
        // 未完成的那片仍是待领状态，可以继续被领
        assertThat(queue.jobs(PipelineStage.BAKE))
                .filteredOn(job -> job.state() == ShardJobState.QUEUED)
                .hasSize(1);
    }

    @Test
    @DisplayName("两个队列实例（模拟两台机器）并发抢占：同一分片不会被领两次")
    void concurrentClaimIsExclusive(@TempDir Path root) throws Exception {
        FileShardQueue machineA = new FileShardQueue(root);
        FileShardQueue machineB = new FileShardQueue(root);
        List<String> all = shards(24);
        machineA.enqueue(PipelineStage.TILE, all);

        Set<String> claimedByA = Collections.synchronizedSet(new HashSet<>());
        Set<String> claimedByB = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);

        for (int i = 0; i < 2; i++) {
            String worker = "a#" + i;
            new Thread(() -> {
                try {
                    start.await();
                    drain(machineA, worker, claimedByA);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
            String workerB = "b#" + i;
            new Thread(() -> {
                try {
                    start.await();
                    drain(machineB, workerB, claimedByB);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

        // 每个分片只被一台机器领到；两边加起来等于全部分片
        Set<String> overlap = new HashSet<>(claimedByA);
        overlap.retainAll(claimedByB);
        assertThat(overlap).isEmpty();
        Set<String> union = new HashSet<>(claimedByA);
        union.addAll(claimedByB);
        assertThat(union).containsExactlyInAnyOrderElementsOf(all);
        assertThat(queueDone(root)).isEqualTo(all.size());
        // 锁文件在完成后都应清掉
        assertThat(lockFiles(root)).isEmpty();
    }

    @Test
    @DisplayName("租约到期：崩溃 worker 的分片会被别的 worker 回收重跑，attempts 递增")
    void expiredLeaseIsReclaimable(@TempDir Path root) throws Exception {
        FileShardQueue queue = new FileShardQueue(root);
        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0"));

        ShardJob first = queue.claim("crashed-worker", Duration.ofMillis(1), PipelineStage.BAKE)
                .orElseThrow();
        assertThat(first.workerId()).isEqualTo("crashed-worker");
        assertThat(first.attempts()).isEqualTo(1);
        Thread.sleep(20);

        ShardJob second = queue.claim("alive-worker", Duration.ofMinutes(5), PipelineStage.BAKE)
                .orElseThrow();
        assertThat(second.workerId()).isEqualTo("alive-worker");
        assertThat(second.attempts()).isEqualTo(2);
        queue.complete(PipelineStage.BAKE, "r.0.0", List.of("ok"));
        assertThat(queue.jobs(PipelineStage.BAKE).get(0).state()).isEqualTo(ShardJobState.DONE);
        assertThat(queue.jobs(PipelineStage.BAKE).get(0).artifacts()).containsExactly("ok");
    }

    @Test
    @DisplayName("失败分片保留原因，不自动重试；clear 后重新入队")
    void failureAndClear(@TempDir Path root) {
        FileShardQueue queue = new FileShardQueue(root);
        queue.enqueue(PipelineStage.LOD, List.of("r.0.0"));
        ShardJob job = queue.claim("w", Duration.ofMinutes(1), PipelineStage.LOD).orElseThrow();
        queue.fail(PipelineStage.LOD, job.shard(), "OOM");

        ShardJob failed = queue.jobs(PipelineStage.LOD).get(0);
        assertThat(failed.state()).isEqualTo(ShardJobState.FAILED);
        assertThat(failed.error()).contains("OOM");
        assertThat(queue.claim("w2", Duration.ofMinutes(1), PipelineStage.LOD)).isEmpty();

        queue.clear(PipelineStage.LOD);
        assertThat(queue.jobs(PipelineStage.LOD)).isEmpty();
        queue.enqueue(PipelineStage.LOD, List.of("r.0.0"));
        assertThat(queue.jobs(PipelineStage.LOD).get(0).state()).isEqualTo(ShardJobState.QUEUED);
    }

    /**
     * 回归：分片键在各阶段是重名的（BAKE 的 r.0.0 与 TILE 的 r.0.0 互不相干），
     * 领取必须限定阶段——否则 BAKE 的 worker 会抢走 TILE 的分片，干错的活
     * 并把对方的作业挂在租约上（曾经真实发生过）。
     */
    @Test
    @DisplayName("领取限定阶段：BAKE worker 永远拿不到 TILE 的分片")
    void claimIsScopedToStage(@TempDir Path root) {
        FileShardQueue queue = new FileShardQueue(root);
        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0"));
        queue.enqueue(PipelineStage.TILE, List.of("r.0.0", "r.0.1"));

        // 反复领 BAKE：只能拿到 BAKE 那一片，TILE 的两片必须纹丝不动
        assertThat(queue.claim("bake", Duration.ofMinutes(5), PipelineStage.BAKE).orElseThrow().shard())
                .isEqualTo("r.0.0");
        assertThat(queue.claim("bake", Duration.ofMinutes(5), PipelineStage.BAKE)).isEmpty();
        assertThat(queue.jobs(PipelineStage.TILE))
                .allMatch(job -> job.state() == ShardJobState.QUEUED);

        // TILE 侧自己领得到
        assertThat(queue.claim("tile", Duration.ofMinutes(5), PipelineStage.TILE).orElseThrow().stage())
                .isEqualTo(PipelineStage.TILE);
    }

    private static void drain(FileShardQueue queue, String worker, Set<String> out)
            throws InterruptedException {
        while (true) {
            ShardJob job = queue.claim(worker, Duration.ofMinutes(5), PipelineStage.TILE).orElse(null);
            if (job == null) {
                return;
            }
            out.add(job.shard());
            Thread.sleep(1);
            queue.complete(PipelineStage.TILE, job.shard(), List.of("tiles/" + job.shard()));
        }
    }

    private static long queueDone(Path root) {
        return new FileShardQueue(root).jobs(PipelineStage.TILE).stream()
                .filter(job -> job.state() == ShardJobState.DONE)
                .count();
    }

    private static List<String> lockFiles(Path root) {
        try (var files = Files.list(root.resolve("tile"))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".lock"))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
