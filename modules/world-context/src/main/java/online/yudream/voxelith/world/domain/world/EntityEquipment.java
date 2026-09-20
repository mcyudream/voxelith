package online.yudream.voxelith.world.domain.world;

import java.util.List;

/**
 * 盔甲架身上的**装备**：四格盔甲（脚/腿/胸/头）+ 双手物品。
 *
 * <p>只记物品 id（如 {@code minecraft:iron_chestplate}），NBT 的组件/标签（染色、附魔等）
 * 不建模——地图渲染只需要「这一格是什么物品」来选贴图与几何。空槽为 {@code null}。</p>
 *
 * @param feet     脚部（靴子）
 * @param legs     腿部（护腿）
 * @param chest    胸部（胸甲）
 * @param head     头部（头盔）
 * @param mainHand 主手物品
 * @param offHand  副手物品
 */
public record EntityEquipment(String feet, String legs, String chest, String head,
                              String mainHand, String offHand) {

    /** 全空装备（存档里绝大多数实体都是）。 */
    public static final EntityEquipment EMPTY =
            new EntityEquipment(null, null, null, null, null, null);

    /** 已填的盔甲槽数（诊断输出用）。 */
    public int armorSlotsFilled() {
        return nonNull(feet) + nonNull(legs) + nonNull(chest) + nonNull(head);
    }

    /** 已填的手部槽数（诊断输出用）。 */
    public int handSlotsFilled() {
        return nonNull(mainHand) + nonNull(offHand);
    }

    /** 四格盔甲（脚、腿、胸、头），供顺序遍历。 */
    public List<String> armorSlots() {
        return List.of(feet, legs, chest, head);
    }

    private static int nonNull(String value) {
        return value == null ? 0 : 1;
    }
}
