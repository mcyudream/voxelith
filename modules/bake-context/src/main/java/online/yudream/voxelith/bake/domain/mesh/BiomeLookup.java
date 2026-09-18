package online.yudream.voxelith.bake.domain.mesh;

import java.util.Optional;

/**
 * 群系查询端口：染色只需要「某个世界坐标是什么群系」。
 *
 * <p>烘焙链路从 {@link online.yudream.voxelith.world.application.WorldBlockAccess} 取群系；
 * 而离屏预览（地表色图）已经手持区块截面、不想再走一次带缓存的随机访问，于是直接以
 * 内存中的群系容器满足同一契约——两边共用同一份染色规则，不会各写一套而漂移。</p>
 */
@FunctionalInterface
public interface BiomeLookup {

    /** 世界绝对坐标处的群系 id（如 minecraft:plains）；无数据时 empty。 */
    Optional<String> biomeAt(int x, int y, int z);
}
