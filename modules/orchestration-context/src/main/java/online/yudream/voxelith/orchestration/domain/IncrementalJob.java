package online.yudream.voxelith.orchestration.domain;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;

import java.util.List;

/**
 * 增量重渲染作业：一次防抖窗口内合并的若干 region，
 * 管线按 bake→tile→lod 重跑这些 region，最后局部失效清单哈希。
 */
public record IncrementalJob(String mapId, List<RegionPos> regions, long scheduledAt) {

    public IncrementalJob {
        regions = List.copyOf(regions);
    }

    public boolean empty() {
        return regions.isEmpty();
    }
}
