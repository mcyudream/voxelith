package online.yudream.voxelith.runtime.application;

import java.nio.file.Path;

/**
 * 模型获取报告落盘端口：把 {@link ModelAcquisition} 标记（含是否走了兜底）写入
 * 工作目录，供管线确认门与排障核查。实现位于 infrastructure。
 */
public interface ModelAcquisitionSink {

    /** 写 model-acquisition.json 到 workDir。 */
    void write(Path workDir, ModelAcquisition acquisition);
}
