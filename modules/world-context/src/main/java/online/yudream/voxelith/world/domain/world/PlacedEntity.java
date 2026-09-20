package online.yudream.voxelith.world.domain.world;

/**
 * 存档里的一个**已放置实体**（用于给地图补几何；方块数据里看不到它们）。
 *
 * <p>只带渲染所需的字段：类型、位置、朝向，盔甲架特有的几个开关
 * （{@code Small} / {@code ShowArms} / {@code NoBasePlate}），以及身上的装备
 * （四格盔甲 + 双手物品，见 {@link EntityEquipment}）。姿态（Pose）与 NBT 其它字段
 * 暂不建模——地图渲染只需要「看得出是什么、在哪、朝哪、穿什么」。</p>
 *
 * @param type        实体 id（如 {@code minecraft:armor_stand}）
 * @param x/y/z       位置（方块坐标，来自 {@code Pos}，保留小数）
 * @param yaw         水平朝向（度，来自 {@code Rotation[0]}；0 = 朝南）
 * @param small       盔甲架是否小型（整体缩放 0.5）
 * @param showArms    盔甲架是否显示手臂（原版默认不显示）
 * @param noBasePlate 盔甲架是否没有底座
 * @param equipment   身上的装备（非盔甲架实体恒为空）
 */
public record PlacedEntity(String type, double x, double y, double z, float yaw,
                           boolean small, boolean showArms, boolean noBasePlate,
                           EntityEquipment equipment) {

    public static final String ARMOR_STAND = "minecraft:armor_stand";

    public PlacedEntity {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("实体类型不能为空");
        }
        equipment = equipment == null ? EntityEquipment.EMPTY : equipment;
    }

    /** 兼容构造：不带装备的调用方（盔甲架本体渲染不依赖装备）。 */
    public PlacedEntity(String type, double x, double y, double z, float yaw,
                        boolean small, boolean showArms, boolean noBasePlate) {
        this(type, x, y, z, yaw, small, showArms, noBasePlate, EntityEquipment.EMPTY);
    }

    public boolean isArmorStand() {
        return ARMOR_STAND.equals(type);
    }
}
