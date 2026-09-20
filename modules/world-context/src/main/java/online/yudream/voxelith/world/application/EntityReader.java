package online.yudream.voxelith.world.application;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.world.PlacedEntity;

import java.util.List;

/**
 * 放置实体读取：把存档里的实体（盔甲架等）读成渲染链路能消费的几何来源。
 *
 * <p>实体不在方块数据里，纯方块渲染看不到它们。与地图画一样，这里只提供**位置与类型**，
 * 具体画成什么形状由渲染侧决定（见 {@code EntityInjector}）。</p>
 */
public interface EntityReader {

    /** 该 region 内支持的实体（按类型过滤后）。 */
    List<PlacedEntity> entities(RegionPos region);
}
