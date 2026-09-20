package online.yudream.voxelith.orchestration.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 分片作业队列端口（分布式渲染的执行面）。
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>{@link #enqueue} 幂等：已完成的分片不重置，已领取且租约未过期的分片不抢；
 *       租约过期的分片重新变回可领取（原 worker 崩了不能把分片卡死）；</li>
 *   <li>{@link #claim} 必须对共享队列目录并发安全（多机多进程抢同一批分片）；</li>
 *   <li>失败的分片保留 FAILED 状态与原因，不自动重试——重试策略属于上层
 *       （重跑整条管线时 enqueue 会把它重新放回队列）。</li>
 * </ul>
 *
 * <p>实现落在 infrastructure（文件系统租约队列），因此不引入 ZooKeeper/Redis 之类的
 * 外部依赖：多个 worker 只要能看见同一个目录（本地、NFS、SMB 均可）就能协作。</p>
 */
public interface ShardQueuePort {

    /** 入队（幂等）：只补充缺失的分片，已 DONE 的保持不动。 */
    void enqueue(PipelineStage stage, List<String> shards);

    /**
     * 领取一个可执行分片并写入租约。
     *
     * @param workerId 领取者标识（写入作业，便于排查是哪台机器在跑）
     * @param lease    租约时长；worker 崩溃后租约到期，其他 worker 可重新领取
     * @return 领到的作业；没有可领分片时为空
     */
    Optional<ShardJob> claim(String workerId, Duration lease);

    /** 标记完成并回填产物（相对 runDir 的路径）。 */
    void complete(PipelineStage stage, String shard, List<String> artifacts);

    /** 标记失败并记录原因。 */
    void fail(PipelineStage stage, String shard, String error);

    /** 某阶段的全部分片作业（观测/收尾用）。 */
    List<ShardJob> jobs(PipelineStage stage);

    /** 清空某阶段的队列（重跑整阶段时用）。 */
    void clear(PipelineStage stage);

    /**
     * 丢弃单个分片的队列记录（作业文件 + 锁），使其可以重新入队。
     *
     * <p>用途：检查点说某分片完成、但产物已从盘上消失时，队列里的 DONE 记录会
     * 挡住重跑——必须先把记录清掉再入队。</p>
     */
    void reset(PipelineStage stage, String shard);
}
