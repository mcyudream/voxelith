package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.ByteTag;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.DoubleTag;
import online.yudream.voxelith.world.domain.nbt.FloatTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.PlacedEntity;
import online.yudream.voxelith.world.testfixtures.AnvilRegionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体读取（盔甲架）：两种实体存放位置、按维度定位、以及 Small/ShowArms/NoBasePlate/朝向。
 *
 * <p>实体不在方块数据里——这条链路读不到就等于「盔甲架没渲染」，所以两条路径都要有用例。</p>
 */
class AnvilEntityReaderTest {

    private static CompoundTag armorStand(double x, double y, double z, float yaw,
                                          boolean small, boolean showArms, boolean noBasePlate) {
        CompoundTag entity = new CompoundTag(Map.of(
                "id", new StringTag("minecraft:armor_stand"),
                "Pos", new ListTag(Tag.DOUBLE, List.of(
                        new DoubleTag(x), new DoubleTag(y), new DoubleTag(z))),
                "Rotation", new ListTag(Tag.FLOAT, List.of(
                        new FloatTag(yaw), new FloatTag(0f))),
                "Small", new ByteTag((byte) (small ? 1 : 0)),
                "ShowArms", new ByteTag((byte) (showArms ? 1 : 0)),
                "NoBasePlate", new ByteTag((byte) (noBasePlate ? 1 : 0))));
        return entity;
    }

    private static CompoundTag otherEntity() {
        return new CompoundTag(Map.of(
                "id", new StringTag("minecraft:pig"),
                "Pos", new ListTag(Tag.DOUBLE, List.of(
                        new DoubleTag(1), new DoubleTag(2), new DoubleTag(3)))));
    }

    private static void writeEntities(Path dimensionRoot, RegionPos region,
                                      List<CompoundTag> entities, String key) {
        CompoundTag chunk = new CompoundTag(Map.of(key, new ListTag(Tag.COMPOUND, List.copyOf(entities))));
        new AnvilRegionWriter().putChunk(new ChunkPos(0, 0), chunk)
                .write(dimensionRoot.resolve("entities"), region);
    }

    @Test
    @DisplayName("两种实体存放位置都能读到盔甲架，非白名单实体被忽略")
    void readsArmorStandsFromBothLocations(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        // 主世界：1.20.2+ / Paper 的 entities 目录
        writeEntities(worldRoot, region,
                List.of(armorStand(12.5, 70, -3.5, 90f, false, true, false), otherEntity()),
                "Entities");
        // 下界：更早的原版写法（区块 NBT 里的小写 entities）
        writeEntities(worldRoot.resolve("DIM-1"), region,
                List.of(armorStand(1.5, 40, 2.5, 0f, true, false, true)),
                "entities");

        List<PlacedEntity> overworld = AnvilEntityReader.of(worldRoot, "minecraft:overworld")
                .entities(region);
        assertThat(overworld).hasSize(1);
        PlacedEntity stand = overworld.getFirst();
        assertThat(stand.type()).isEqualTo(PlacedEntity.ARMOR_STAND);
        assertThat(stand.x()).isEqualTo(12.5);
        assertThat(stand.y()).isEqualTo(70);
        assertThat(stand.z()).isEqualTo(-3.5);
        assertThat(stand.yaw()).isEqualTo(90f);
        assertThat(stand.small()).isFalse();
        assertThat(stand.showArms()).isTrue();
        assertThat(stand.noBasePlate()).isFalse();

        List<PlacedEntity> nether = AnvilEntityReader.of(worldRoot, "minecraft:the_nether")
                .entities(region);
        assertThat(nether).as("下界实体在 DIM-1 下，必须按维度定位").hasSize(1);
        assertThat(nether.getFirst().small()).isTrue();
        assertThat(nether.getFirst().noBasePlate()).isTrue();
        assertThat(nether.getFirst().showArms()).isFalse();
    }

    @Test
    @DisplayName("没有实体 / 没有该 region 文件时返回空，不抛错")
    void emptyWhenNoEntities(@TempDir Path worldRoot) {
        assertThat(AnvilEntityReader.of(worldRoot, "minecraft:overworld")
                .entities(new RegionPos(5, 5))).isEmpty();
    }

    @Test
    @DisplayName("装备（旧版格式）：ArmorItems=[脚,腿,胸,头]、HandItems=[主手,副手]，空槽为 null")
    void readsLegacyEquipment(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        Map<String, Tag> entries = new LinkedHashMap<>();
        entries.put("id", new StringTag("minecraft:armor_stand"));
        entries.put("Pos", new ListTag(Tag.DOUBLE, List.of(
                new DoubleTag(0), new DoubleTag(64), new DoubleTag(0))));
        entries.put("ArmorItems", new ListTag(Tag.COMPOUND, List.of(
                item("minecraft:iron_boots"),
                new CompoundTag(Map.of()),
                item("minecraft:diamond_chestplate"),
                item("minecraft:iron_helmet"))));
        entries.put("HandItems", new ListTag(Tag.COMPOUND, List.of(
                item("minecraft:diamond_sword"),
                new CompoundTag(Map.of()))));
        writeEntities(worldRoot, region,
                List.of(new CompoundTag(entries)), "Entities");

        List<PlacedEntity> entities = AnvilEntityReader.of(worldRoot, "minecraft:overworld")
                .entities(region);
        assertThat(entities).hasSize(1);
        var equipment = entities.getFirst().equipment();
        assertThat(equipment.feet()).isEqualTo("minecraft:iron_boots");
        assertThat(equipment.legs()).as("空槽（空复合标签）读成 null").isNull();
        assertThat(equipment.chest()).isEqualTo("minecraft:diamond_chestplate");
        assertThat(equipment.head()).isEqualTo("minecraft:iron_helmet");
        assertThat(equipment.mainHand()).isEqualTo("minecraft:diamond_sword");
        assertThat(equipment.offHand()).isNull();
    }

    @Test
    @DisplayName("装备（1.20.5+ 格式）：equipment 复合标签按槽位键读出，缺的槽为 null")
    void readsItemComponentEquipment(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        Map<String, Tag> entries = new LinkedHashMap<>();
        entries.put("id", new StringTag("minecraft:armor_stand"));
        entries.put("Pos", new ListTag(Tag.DOUBLE, List.of(
                new DoubleTag(0), new DoubleTag(64), new DoubleTag(0))));
        entries.put("equipment", new CompoundTag(Map.of(
                "mainhand", item("minecraft:golden_sword"),
                "head", item("minecraft:turtle_helmet"),
                "chest", item("minecraft:netherite_chestplate"))));
        writeEntities(worldRoot, region,
                List.of(new CompoundTag(entries)), "Entities");

        var equipment = AnvilEntityReader.of(worldRoot, "minecraft:overworld")
                .entities(region).getFirst().equipment();
        assertThat(equipment.head()).isEqualTo("minecraft:turtle_helmet");
        assertThat(equipment.chest()).isEqualTo("minecraft:netherite_chestplate");
        assertThat(equipment.legs()).isNull();
        assertThat(equipment.feet()).isNull();
        assertThat(equipment.mainHand()).isEqualTo("minecraft:golden_sword");
        assertThat(equipment.offHand()).isNull();
    }

    /** 存档里的物品复合标签：只关心 id（组件/标签不建模）。 */
    private static CompoundTag item(String id) {
        return new CompoundTag(Map.of(
                "id", new StringTag(id),
                "count", new ByteTag((byte) 1)));
    }
}
