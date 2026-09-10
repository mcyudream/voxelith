package online.yudream.voxelith.sharedkernel.vo;

/**
 * Region 坐标（32×32 区块 = 512×512 方块），渲染任务分片与增量更新的最小调度单元。
 */
public record RegionPos(int x, int z) {

    public String fileName() {
        return "r." + x + "." + z + ".mca";
    }
}
