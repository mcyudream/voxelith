package online.yudream.voxelith.runtime.domain;

/**
 * worker 子进程启动器端口。实现位于 infrastructure（进程隔离，见 ADR 0001）。
 * 启动器负责：写 spec.json → 拉起子 JVM → 等待结束（带超时）→ 读 worker-result.json。
 */
public interface RuntimeWorkerLauncher {

    /** 以给定规格启动 worker 并等待其完成，返回解析后的报告。 */
    HarvestReport launch(RuntimeSpec spec);
}
