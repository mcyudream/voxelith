package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.application.EntityReader;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.DoubleTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.PlacedEntity;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 存档实体读取（当前用于盔甲架的简化几何）。
 *
 * <p>支持的实体类型是一张**显式白名单**：宁可少画，也不要凭空给不认识的实体编几何。
 * 目前只有 {@code minecraft:armor_stand}；盔甲/手持物品等装备不渲染（需要实体贴图，
 * 不在方块图集里），姿态（Pose）也按直立处理。</p>
 */
public final class AnvilEntityReader implements EntityReader {

    /** 支持的实体 id（白名单）。 */
    private static final List<String> SUPPORTED = List.of(PlacedEntity.ARMOR_STAND);

    private final AnvilEntitySource source;

    /** 兼容构造：主世界（维度目录 = 存档根）。 */
    public AnvilEntityReader(Path dimensionRoot) {
        this.source = new AnvilEntitySource(dimensionRoot);
    }

    /** 组合根入口：按维度 id 定位（下界 DIM-1、末地 DIM1）。 */
    public static AnvilEntityReader of(Path worldRoot, String dimension) {
        return new AnvilEntityReader(WorldContextBootstrap.dimensionDir(worldRoot, dimension));
    }

    @Override
    public List<PlacedEntity> entities(RegionPos region) {
        List<PlacedEntity> out = new ArrayList<>();
        for (CompoundTag entity : source.entities(region)) {
            String type = entity.contains("id") ? entity.getString("id") : null;
            if (type == null || !SUPPORTED.contains(type)) {
                continue;
            }
            ListTag pos = entity.contains("Pos") ? entity.getList("Pos") : null;
            if (pos == null || pos.size() < 3) {
                continue;
            }
            out.add(new PlacedEntity(
                    type,
                    doubleOf(pos.get(0)),
                    doubleOf(pos.get(1)),
                    doubleOf(pos.get(2)),
                    yawOf(entity),
                    flag(entity, "Small"),
                    flag(entity, "ShowArms"),
                    flag(entity, "NoBasePlate")));
        }
        return List.copyOf(out);
    }

    /** {@code Rotation[0]} 是水平朝向（度）；缺省 0 = 朝南，与实体默认朝向一致。 */
    private static float yawOf(CompoundTag entity) {
        if (!entity.contains("Rotation")) {
            return 0f;
        }
        ListTag rotation = entity.getList("Rotation");
        if (rotation.size() < 1) {
            return 0f;
        }
        Tag first = rotation.get(0);
        return first instanceof online.yudream.voxelith.world.domain.nbt.FloatTag f
                ? f.value()
                : (float) doubleOf(first);
    }

    /** 存档里布尔量是 byte（1 = true）。 */
    private static boolean flag(CompoundTag entity, String key) {
        return entity.contains(key) && entity.getByte(key) != 0;
    }

    private static double doubleOf(Tag tag) {
        if (tag instanceof DoubleTag d) {
            return d.value();
        }
        if (tag instanceof online.yudream.voxelith.world.domain.nbt.FloatTag f) {
            return f.value();
        }
        return 0d;
    }
}
