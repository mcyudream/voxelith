package online.yudream.voxelith.orchestration.domain;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;

/**
 * 存档 region 目录监听端口（WatchService 式）。
 * 实现方在独立线程上监听 {@code *.mca} 的 CREATE/MODIFY/DELETE，
 * 把事件投递给 {@link RegionChangeListener}；关闭后停止监听。
 */
public interface RegionWatchPort extends AutoCloseable {

    @FunctionalInterface
    interface RegionChangeListener {
        void onRegionChanged(RegionPos region);
    }

    void start(RegionChangeListener listener);

    @Override
    void close();
}
