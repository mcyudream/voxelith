package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.ByteArrayTag;
import online.yudream.voxelith.world.domain.nbt.ByteTag;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.DoubleTag;
import online.yudream.voxelith.world.domain.nbt.IntTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.MapArtFrame;
import online.yudream.voxelith.world.infrastructure.nbt.NbtWriter;
import online.yudream.voxelith.world.testfixtures.AnvilRegionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 展示框识别与维度定位（用合成存档，不依赖本机真实世界）：
 *
 * <ul>
 *   <li><b>1.20.5+</b>：物品栈改用数据组件 {@code Item.components."minecraft:map_id"}，
 *       旧的 {@code Item.tag.map} 不复存在——此前这条路径取不到地图编号，
 *       于是每个展示框都被丢掉，**地图画和展示框一个都不渲染**（复现过的线上问题）；</li>
 *   <li><b>更早版本</b>：{@code Item.tag.map}；</li>
 *   <li>空白地图与非地图物品不能被当成地图画；</li>
 *   <li>下界/末地的展示框在 {@code DIM-1}/{@code DIM1} 目录下，而地图颜色始终在存档根的 {@code data/}。</li>
 * </ul>
 */
class AnvilMapArtReaderFormatTest {

    /** 1.20.5+ 风格的展示框：地图编号在数据组件里。 */
    private static CompoundTag modernFrame(int x, int y, int z, int mapId) {
        CompoundTag components = new CompoundTag(Map.of("minecraft:map_id", new IntTag(mapId)));
        CompoundTag item = new CompoundTag(Map.of(
                "id", new StringTag("minecraft:filled_map"),
                "count", new IntTag(1),
                "components", components));
        return new CompoundTag(Map.of(
                "id", new StringTag("minecraft:item_frame"),
                "TileX", new IntTag(x),
                "TileY", new IntTag(y),
                "TileZ", new IntTag(z),
                "Facing", new ByteTag((byte) 3),
                "Item", item));
    }

    /** 1.20.4 及更早风格的展示框：地图编号在 Item.tag.map（坐标走 Pos 浮点）。 */
    private static CompoundTag legacyFrame(int mapId, double px, double py, double pz) {
        CompoundTag tag = new CompoundTag(Map.of("map", new IntTag(mapId)));
        CompoundTag item = new CompoundTag(Map.of(
                "id", new StringTag("minecraft:filled_map"),
                "tag", tag));
        ListTag pos = new ListTag(Tag.DOUBLE, List.of(
                new DoubleTag(px), new DoubleTag(py), new DoubleTag(pz)));
        return new CompoundTag(Map.of(
                "id", new StringTag("minecraft:item_frame"),
                "Pos", pos,
                "Item", item));
    }

    /** 1.20.5+ 的空白地图（minecraft:map，没有 map_id 组件）。 */
    private static CompoundTag emptyMapFrame() {
        return new CompoundTag(Map.of(
                "id", new StringTag("minecraft:item_frame"),
                "Item", new CompoundTag(Map.of(
                        "id", new StringTag("minecraft:map"),
                        "count", new IntTag(1)))));
    }

    /** 展示框里放的不是地图（即便模型带了同名组件）。 */
    private static CompoundTag paintingFrame(int mapId) {
        CompoundTag components = new CompoundTag(Map.of("minecraft:map_id", new IntTag(mapId)));
        return new CompoundTag(Map.of(
                "id", new StringTag("minecraft:item_frame"),
                "Item", new CompoundTag(Map.of(
                        "id", new StringTag("minecraft:painting"),
                        "components", components))));
    }

    /** 写一份 entities/r.X.Z.mca（Paper 与 1.20.2+ 原版都是这个位置）。 */
    private static void writeEntities(Path dimensionRoot, RegionPos region,
                                      List<CompoundTag> frames) {
        CompoundTag chunk = new CompoundTag(Map.of(
                "Entities", new ListTag(Tag.COMPOUND, List.copyOf(frames))));
        new AnvilRegionWriter().putChunk(new ChunkPos(0, 0), chunk)
                .write(dimensionRoot.resolve("entities"), region);
    }

    /** 更早版本的原版：实体在 region 文件里，键名小写 entities。 */
    private static void writeEntitiesInRegion(Path dimensionRoot, RegionPos region,
                                              List<CompoundTag> frames) {
        CompoundTag chunk = new CompoundTag(Map.of(
                "entities", new ListTag(Tag.COMPOUND, List.copyOf(frames))));
        new AnvilRegionWriter().putChunk(new ChunkPos(0, 0), chunk)
                .write(dimensionRoot.resolve("region"), region);
    }

    /** 写 data/map_N.dat（gzip NBT，{data:{colors:[...]}}）。 */
    private static void writeMap(Path worldRoot, int mapId) {
        byte[] colors = new byte[AnvilMapArtReader.MAP_SIZE * AnvilMapArtReader.MAP_SIZE];
        Arrays.fill(colors, (byte) ((1 << 2) | 2));   // 色 id 1（GRASS）+ 满亮度档
        CompoundTag data = new CompoundTag(Map.of("colors", new ByteArrayTag(colors)));
        CompoundTag root = new CompoundTag(Map.of("data", data));
        Path file = worldRoot.resolve("data").resolve("map_" + mapId + ".dat");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new NbtWriter().writeNamedRoot("", root, true));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("1.20.5+ 数据组件与旧的 tag.map 两种写法都能读出地图编号")
    void readsBothItemFormats(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        writeEntities(worldRoot, region, List.of(modernFrame(10, 64, 20, 7)));
        writeEntities(worldRoot.resolve("DIM-1"), region, List.of(legacyFrame(9, 16.5, 70.5, 8.5)));
        writeMap(worldRoot, 7);
        writeMap(worldRoot, 9);

        List<MapArtFrame> overworld = new AnvilMapArtReader(worldRoot).frames(region);
        assertThat(overworld).hasSize(1);
        assertThat(overworld.getFirst()).isEqualTo(new MapArtFrame(10, 64, 20, "south", 7));

        List<MapArtFrame> nether = AnvilMapArtReader.of(worldRoot, "minecraft:the_nether")
                .frames(region);
        assertThat(nether).as("下界展示框在 DIM-1/entities 下，必须按维度定位").hasSize(1);
        assertThat(nether.getFirst()).isEqualTo(new MapArtFrame(16, 70, 8, "south", 9));
    }

    @Test
    @DisplayName("更早版本的原版存档：实体在 region 文件的 entities 列表里也能读")
    void readsEntitiesInsideRegionFile(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        writeEntitiesInRegion(worldRoot, region, List.of(legacyFrame(3, 1.5, 2.5, 3.5)));
        writeMap(worldRoot, 3);

        List<MapArtFrame> frames = new AnvilMapArtReader(worldRoot).frames(region);
        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().mapId()).isEqualTo(3);
    }

    @Test
    @DisplayName("空白地图与非地图物品都不算地图画")
    void ignoresNonMapItems(@TempDir Path worldRoot) {
        writeEntities(worldRoot, new RegionPos(0, 0),
                List.of(emptyMapFrame(), paintingFrame(5)));
        assertThat(new AnvilMapArtReader(worldRoot).frames(new RegionPos(0, 0))).isEmpty();
    }

    @Test
    @DisplayName("地图颜色始终从存档根的 data/ 读（与维度无关）")
    void mapColorsLiveAtWorldRoot(@TempDir Path worldRoot) {
        RegionPos region = new RegionPos(0, 0);
        writeEntities(worldRoot.resolve("DIM1"), region, List.of(legacyFrame(5, 0.5, 1.5, 2.5)));
        writeMap(worldRoot, 5);

        int[] colors = AnvilMapArtReader.of(worldRoot, "minecraft:the_end").mapColors(5).orElseThrow();
        assertThat(colors).hasSize(AnvilMapArtReader.MAP_SIZE * AnvilMapArtReader.MAP_SIZE);
        assertThat(colors[0] >>> 24).as("已填充地图必须是不透明的").isEqualTo(0xFF);
    }
}
