package online.yudream.voxelith.world.application;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.MapArtFrame;

import java.util.List;
import java.util.Optional;

/**
 * 地图画读取端口：物品展示框（实体）+ 已填充地图的颜色数据。
 *
 * <p>为什么要单独一个端口：展示框是实体、地图颜色在 {@code data/map_*.dat}，
 * 都不在方块数据里，{@link WorldBlockAccess} 覆盖不到。</p>
 */
public interface MapArtReader {

    /**
     * 读一个 region 内的全部地图画展示框（世界坐标）。
     *
     * <p>同时兼容两种实体存放方式：Paper 的独立 {@code entities/r.X.Z.mca}，
     * 以及原版把 {@code entities} 列表放在区块 NBT 里的写法。</p>
     */
    List<MapArtFrame> frames(RegionPos region);

    /**
     * 地图颜色：128×128 ARGB（行主序，行 0 = 地图北边）。
     *
     * @return 地图文件不存在时为空
     */
    Optional<int[]> mapColors(int mapId);
}
