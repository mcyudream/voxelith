package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardJob;
import online.yudream.voxelith.orchestration.domain.ShardJobState;
import online.yudream.voxelith.orchestration.infrastructure.shard.FileShardQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片 worker 池的并发回归：**别人正在跑**与**别人抢错阶段**这两个坑。
 *
 * <p>两者都曾经真实存在：前者让阶段在缺瓦片的情况下被判成完成，
 * 后者让 BAKE worker 干了 TILE 的活并把 TILE 的分片卡在租约上 30 分钟。</p>
 */
class ShardWorkerPoolConcurrencyTest {

    @TempDir
    Path queueDir;

    @Test
    @DisplayName("远端持租约时本地要等：等到对方回填 DONE 才算跑完")
    void waitsWhileRemoteHoldsLease() throws Exception {
        FileShardQueue queue = new FileShardQueue(queueDir);
        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0", "r.0.1"));

        // 远端先抢走一片并持有 30 分钟租约（模拟还在跑）
        ShardJob remote = queue.claim("remote#0", Duration.ofMinutes(30), PipelineStage.BAKE)
                .orElseThrow();
        assertThat(remote.shard()).isEqualTo("r.0.0");

        // 远端 300ms 后把活干完（回填 DONE），本地 drain 必须一直等到那一刻
        CountDownLatch remoteDone = new CountDownLatch(1);
        Thread remoteWorker = new Thread(() -> {
            try {
                Thread.sleep(300);
                queue.complete(PipelineStage.BAKE, remote.shard(), List.of("tiles/r.0.0.done"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                remoteDone.countDown();
            }
        });
        remoteWorker.start();

        ShardWorkerPool pool = new ShardWorkerPool(queue, Duration.ofMinutes(1), Duration.ofSeconds(30));
        Set<String> locals = ConcurrentHashMap.newKeySet();
        List<ShardJob> jobs = pool.drainStage(PipelineStage.BAKE, "local", 2, (stage, shard) -> {
            locals.add(shard);
            return List.of("tiles/" + shard + ".done");
        });
        remoteWorker.join(TimeUnit.SECONDS.toMillis(5));

        // 本地只该跑自己领到的那片；远端那片由远端完成，但整体必须等齐
        assertThat(locals).containsExactly("r.0.1");
        assertThat(jobs).allMatch(job -> job.state() == ShardJobState.DONE);
        assertThat(remoteDone.getCount()).isZero();
    }

    @Test
    @DisplayName("多阶段共存：drain BAKE 不会碰 TILE 的分片，也不会把它挂上租约")
    void drainDoesNotTouchOtherStages() {
        FileShardQueue queue = new FileShardQueue(queueDir);
        queue.enqueue(PipelineStage.BAKE, List.of("r.0.0"));
        queue.enqueue(PipelineStage.TILE, List.of("r.0.0", "r.0.1"));

        ShardWorkerPool pool = new ShardWorkerPool(queue, Duration.ofMinutes(1), Duration.ofSeconds(30));
        Set<String> handled = ConcurrentHashMap.newKeySet();
        List<ShardJob> jobs = pool.drainStage(PipelineStage.BAKE, "local", 2, (stage, shard) -> {
            assertThat(stage).isEqualTo(PipelineStage.BAKE);   // handler 绝不收到别的阶段
            handled.add(stage + ":" + shard);
            return List.of("bake/" + shard);
        });

        assertThat(handled).containsExactly("BAKE:r.0.0");
        assertThat(jobs).allMatch(job -> job.state() == ShardJobState.DONE);
        assertThat(queue.jobs(PipelineStage.TILE))
                .as("TILE 的分片必须保持待领取，不能被 BAKE 的 worker 抢走")
                .allMatch(job -> job.state() == ShardJobState.QUEUED)
                .allMatch(job -> job.workerId() == null);
    }

    @Test
    @DisplayName("远端一直不回来的分片会按空闲超时报错，而不是静默收工")
    void reportsTimeoutInsteadOfSilentlyFinishing() {
        FileShardQueue queue = new FileShardQueue(queueDir);
        queue.enqueue(PipelineStage.LOD, List.of("r.0.0"));
        queue.claim("remote#0", Duration.ofMinutes(30), PipelineStage.LOD).orElseThrow();

        ShardWorkerPool pool = new ShardWorkerPool(queue, Duration.ofMinutes(1), Duration.ofMillis(200));
        AtomicInteger executed = new AtomicInteger();
        try {
            List<ShardJob> jobs = pool.drainStage(PipelineStage.LOD, "local", 1, (stage, shard) -> {
                executed.incrementAndGet();
                return List.of("lod/" + shard);
            });
            assertThat(jobs).as("不该正常返回").isEmpty();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("等待分片超时").contains("r.0.0(CLAIMED)");
        }
        assertThat(executed.get()).isZero();
    }
}
