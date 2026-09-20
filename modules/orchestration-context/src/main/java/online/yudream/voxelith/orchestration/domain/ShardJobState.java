package online.yudream.voxelith.orchestration.domain;

/** 分片作业状态。 */
public enum ShardJobState {
    /** 待领取 */
    QUEUED,
    /** 已被某个 worker 领取（租约未过期），领取者崩溃后租约到期可被重新领取 */
    CLAIMED,
    /** 完成（artifacts 已回填） */
    DONE,
    /** 失败（error 已回填），等待人工处理或重跑 */
    FAILED
}
