package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.application.MapArtReader;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.DoubleTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.MapArtFrame;
import online.yudream.voxelith.world.infrastructure.nbt.NbtReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 存档里的地图画读取实现。
 *
 * <ul>
 *   <li><b>展示框</b>：实体。Paper 世界把它放在独立的 {@code entities/r.X.Z.mca}（与 region 同格式），
 *       原版世界则把 {@code Entities}/{@code entities} 列表放在区块 NBT 里；两种都读。</li>
 *   <li><b>地图颜色</b>：{@code data/map_N.dat}（gzip NBT，{@code colors} 为 16384 字节），
 *       按原版地图调色板（62 基色 × 4 明暗档）转成 ARGB。</li>
 * </ul>
 */
public final class AnvilMapArtReader implements MapArtReader {

    /** 地图边长（像素）。 */
    public static final int MAP_SIZE = 128;

    /** 原版地图基色表（id → 0xRRGGBB），顺序与 MapColor 一致。 */
    private static final int[] BASE_COLORS = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
            0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBAA634, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
            0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
            0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796,
    };

    /** 明暗档倍率（Brightness 枚举顺序：LOW / NORMAL / HIGH / LOWEST）。 */
    private static final float[] SHADE = {0.71f, 0.86f, 1.0f, 0.53f};

    /** 展示框实体 id（普通 + 荧光）。 */
    private static final List<String> FRAME_IDS = List.of("minecraft:item_frame", "minecraft:glow_item_frame");

    /** 朝向字节 → 名称（与原版 Direction 顺序一致）。 */
    private static final String[] FACINGS = {"down", "up", "north", "south", "west", "east"};

    private final Path worldDir;
    private final NbtReader nbt = new NbtReader();

    public AnvilMapArtReader(Path worldDir) {
        this.worldDir = worldDir;
    }

    @Override
    public List<MapArtFrame> frames(RegionPos region) {
        List<MapArtFrame> frames = new ArrayList<>();
        // Paper：实体单独存
        Path entitiesFile = worldDir.resolve("entities")
                .resolve("r." + region.x() + "." + region.z() + ".mca");
        readFrames(entitiesFile, frames, true);
        // 原版：实体在区块 NBT 的 entities 列表里
        readFrames(regionFile(region), frames, false);
        return List.copyOf(frames);
    }

    private Path regionFile(RegionPos region) {
        return worldDir.resolve("region").resolve("r." + region.x() + "." + region.z() + ".mca");
    }

    private void readFrames(Path file, List<MapArtFrame> out, boolean paperEntitiesFile) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (AnvilRegionReader reader = new AnvilRegionReader(file)) {
            for (AnvilRegionReader.ChunkEntry entry : reader.listChunks()) {
                Optional<byte[]> payload = reader.readChunkPayload(entry.localX(), entry.localZ());
                if (payload.isEmpty()) {
                    continue;
                }
                CompoundTag root = nbt.readNamedRootAuto(payload.get());
                // Paper 的 entities 文件用 Entities，原版区块 NBT 用 entities
                String key = root.contains("Entities") ? "Entities" : root.contains("entities") ? "entities" : null;
                if (key == null) {
                    continue;
                }
                ListTag list = root.getList(key);
                for (int i = 0; i < list.size(); i++) {
                    frameOf(list.getCompound(i)).ifPresent(out::add);
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析展示框失败: " + file, e);
        }
    }

    private Optional<MapArtFrame> frameOf(CompoundTag entity) {
        if (!entity.contains("id") || !FRAME_IDS.contains(entity.getString("id"))) {
            return Optional.empty();
        }
        if (!entity.contains("Item")) {
            return Optional.empty();
        }
        CompoundTag item = entity.getCompound("Item");
        Integer mapId = mapIdOf(item);
        if (mapId == null) {
            return Optional.empty();
        }
        // 方块坐标优先用 TileX/TileY/TileZ（存档里就是整数），退化用 Pos 取整
        int x;
        int y;
        int z;
        if (entity.contains("TileX")) {
            x = entity.getInt("TileX");
            y = entity.getInt("TileY");
            z = entity.getInt("TileZ");
        } else {
            ListTag pos = entity.getList("Pos");
            if (pos.size() < 3) {
                return Optional.empty();
            }
            x = (int) Math.floor(doubleOf(pos.get(0)));
            y = (int) Math.floor(doubleOf(pos.get(1)));
            z = (int) Math.floor(doubleOf(pos.get(2)));
        }
        int facing = entity.contains("Facing") ? entity.getByte("Facing") & 0xFF : 3;
        String name = facing >= 0 && facing < FACINGS.length ? FACINGS[facing] : "south";
        return Optional.of(new MapArtFrame(x, y, z, name, mapId));
    }

    /** 从物品 NBT 里取地图编号：{@code Item.tag.map}。 */
    private static Integer mapIdOf(CompoundTag item) {
        if (!item.contains("tag")) {
            return null;
        }
        CompoundTag tag = item.getCompound("tag");
        if (!tag.contains("map")) {
            return null;
        }
        int id = tag.getInt("map");
        return id >= 0 ? id : null;
    }

    private static double doubleOf(Tag tag) {
        return tag instanceof DoubleTag d ? d.value() : 0d;
    }

    @Override
    public Optional<int[]> mapColors(int mapId) {
        Path file = worldDir.resolve("data").resolve("map_" + mapId + ".dat");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            CompoundTag root = nbt.readNamedRootAuto(Files.readAllBytes(file));
            // 地图文件的 NBT 是 {data: {...}} 包一层，colors 在 data 里
            CompoundTag data = root.contains("data") ? root.getCompound("data") : root;
            if (!data.contains("colors")) {
                return Optional.empty();
            }
            byte[] raw = data.getByteArray("colors");
            if (raw.length < MAP_SIZE * MAP_SIZE) {
                return Optional.empty();
            }
            int[] argb = new int[MAP_SIZE * MAP_SIZE];
            for (int i = 0; i < argb.length; i++) {
                argb[i] = toArgb(raw[i]);
            }
            return Optional.of(argb);
        } catch (IOException e) {
            throw new UncheckedIOException("读取地图失败: " + file, e);
        }
    }

    /** 地图字节 = {@code (基色 id << 2) | 明暗档}；基色 0（NONE）在原版里是透明的。 */
    static int toArgb(byte packed) {
        int value = packed & 0xFF;
        int base = value >> 2;
        int shade = value & 3;
        if (base <= 0 || base >= BASE_COLORS.length) {
            return 0;
        }
        int rgb = BASE_COLORS[base];
        float f = SHADE[shade];
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
