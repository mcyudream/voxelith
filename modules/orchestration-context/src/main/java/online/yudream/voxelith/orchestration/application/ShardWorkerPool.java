package online.yudream.voxelith.orchestration.application;

import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardJob;
import online.yudream.voxelith.orchestration.domain.ShardQueuePort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 分片 worker 池：在本地起 N 个线程领队列里的分片并执行。
 *
 * <p>与分布式的关系：本池只是**其中一个 worker**。别的进程（{@code shardWorker} 入口）
 * 可以同时从同一个队列目录里领活——谁先抢到谁跑，跑完把产物路径回填进作业文件。
 * 因此「本地单机多线程」与「多机各跑几个线程」走的是同一条代码路径，
 * 差别只在有几个进程连到那个目录。</p>
 *
 * <p>等待语义：所有分片进入终态（DONE/FAILED）才返回；只要还有人（别的 worker）
 * 在推进就不算超时，连续 {@code idleTimeout} 没有新完成的分片才判定卡住。</p>
 */
public class ShardWorkerPool {

    /** 单分片执行器：返回该分片的产物相对路径。 */
    @FunctionalInterface
    public interface ShardHandler {
        List<String> execute(PipelineStage stage, String shard) throws Exception;
    }

    private final ShardQueuePort queue;
    private final Duration lease;
    private final Duration idleTimeout;

    public ShardWorkerPool(ShardQueuePort queue, Duration lease, Duration idleTimeout) {
        this.queue = queue;
        this.lease = lease;
        this.idleTimeout = idleTimeout;
    }

    /**
     * 把某阶段的分片跑完。
     *
     * @param stage      阶段
     * @param workerId   本地线程的标识前缀（写进作业，便于看出来是谁跑的）
     * @param threads    本地并发线程数（1 = 串行；多机协作时每台各自配置）
     * @return 该阶段的全部作业（含其它 worker 完成的）
     * @throws IllegalStateException 空闲超时或分片失败
     */
    public List<ShardJob> drainStage(PipelineStage stage, String workerId, int threads,
                                     ShardHandler handler) {
        int workers = Math.max(1, threads);
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "voxelith-shard-" + stage.name().toLowerCase());
            thread.setDaemon(true);
            return thread;
        });
        long lastProgress = System.currentTimeMillis();
        int terminalBefore = countTerminal(queue.jobs(stage));
        try {
            while (true) {
                List<ShardJob> jobs = queue.jobs(stage);
                long now = System.currentTimeMillis();
                // 收工条件是「全部进入终态」，不是「没有可领取的」：
                // 别的 worker 正持有租约跑来跑去的分片既不可领取、也没完成，
                // 必须继续等——否则阶段会被提前判成完成，图里直接少一片。
                List<ShardJob> open = jobs.stream().filter(job -> !job.terminal()).toList();
                if (open.isEmpty()) {
                    return jobs;
                }
                if (now - lastProgress > idleTimeout.toMillis()) {
                    throw new IllegalStateException("等待分片超时（" + idleTimeout.toSeconds()
                            + "s 无新完成）：" + open.stream()
                            .map(job -> job.shard() + "(" + job.state() + ")").toList());
                }

                if (jobs.stream().anyMatch(job -> job.pending(now))) {
                    runRound(pool, stage, workerId, workers, handler);
                } else {
                    // 全部被别的 worker 持租约：本地没活可干，等一小会儿再看
                    TimeUnit.MILLISECONDS.sleep(100);
                }

                int terminalAfter = countTerminal(queue.jobs(stage));
                if (terminalAfter > terminalBefore) {
                    terminalBefore = terminalAfter;
                    lastProgress = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("分片执行被中断: " + stage, e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 领到没活为止：每次领到就执行并回填状态。 */
    private void runWorker(PipelineStage stage, String workerId, ShardHandler handler) {
        while (true) {
            ShardJob job = queue.claim(workerId, lease, stage).orElse(null);
            if (job == null) {
                return;
            }
            if (job.stage() != stage) {
                // 防御：队列实现若返回了别的阶段的作业，绝不能当成本阶段的分片执行
                // （干错的活 + 把对方卡在租约上）。放回去让别人领。
                queue.reset(job.stage(), job.shard());
                continue;
            }
            try {
                List<String> artifacts = handler.execute(stage, job.shard());
                queue.complete(stage, job.shard(), artifacts);
            } catch (Exception e) {
                queue.fail(stage, job.shard(), String.valueOf(e));
            }
        }
    }

    private void runRound(ExecutorService pool, PipelineStage stage, String workerId, int workers,
                          ShardHandler handler) {
        List<Future<?>> futures = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            String id = workerId + "#" + i;
            futures.add(pool.submit(() -> runWorker(stage, id, handler)));
        }
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("分片执行被中断: " + stage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("分片执行失败: " + stage, e.getCause());
        }
    }

    private static int countTerminal(List<ShardJob> jobs) {
        return (int) jobs.stream().filter(ShardJob::terminal).count();
    }

    /**
     * 等待其它 worker 完成后收尾（本地不干活，只等）：用于「调度进程」形态。
     *
     * <p>同样按「全部进入终态」判定：远端正在跑（CLAIMED 且租约有效）也要继续等。</p>
     */
    public List<ShardJob> awaitStage(PipelineStage stage, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (true) {
            List<ShardJob> jobs = queue.jobs(stage);
            if (jobs.stream().allMatch(ShardJob::terminal)) {
                return jobs;
            }
            long now = System.currentTimeMillis();
            if (now > deadline) {
                throw new IllegalStateException("等待远端 worker 超时: " + stage + "，未完成的分片: "
                        + jobs.stream().filter(job -> !job.terminal())
                        .map(job -> job.shard() + "(" + job.state() + ")").toList());
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断: " + stage, e);
            }
        }
    }
}
