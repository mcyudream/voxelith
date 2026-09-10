package online.yudream.voxelith.world.testfixtures;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.ByteArrayTag;
import online.yudream.voxelith.world.domain.nbt.ByteTag;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.IntArrayTag;
import online.yudream.voxelith.world.domain.nbt.IntTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.LongArrayTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.infrastructure.nbt.NbtWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * legacy 合成存档生成器：按 1.12-/1.13–1.17 的 Level 包装布局生成测试世界，
 * 用于版本适配 golden 测试。
 *
 * <p>{@link Format#NUMERIC_1_12}：Level.Sections 用 Blocks/Add/Data 字节数组 + Level.Biomes byte[256]，
 * 方块以数字 id/meta 写入（{@link #setBlock(int, int, int, int, int)}）。
 * {@link Format#PALETTED_1_15}：Level.Sections 用 Palette/BlockStates（1.15 旧跨 long 打包）
 * + Level.Biomes int[1024]，方块以扁平化状态串写入（{@link #setBlock(int, int, int, String)}）。</p>
 */
public final class LegacySyntheticWorldBuilder {

    public static final int DATA_VERSION_1_12_2 = 1343;
    public static final int DATA_VERSION_1_15_2 = 2230;

    public enum Format { NUMERIC_1_12, PALETTED_1_15 }

    private final Format format;
    private final int dataVersion;
    /** 数值格式：value = (id << 4) | meta；调色板格式：扁平化状态串。 */
    private final Map<Long, Integer> numericBlocks = new TreeMap<>();
    private final Map<Long, String> stateBlocks = new TreeMap<>();
    /** 数值格式列群系 byte[256]（chunk 相对坐标 z*16+x → legacy biome id）。 */
    private final Map<ChunkPos, byte[]> columnBiomes = new LinkedHashMap<>();
    /** 调色板格式 quart 群系 int[1024]。 */
    private final Map<ChunkPos, int[]> quartBiomes = new LinkedHashMap<>();

    public LegacySyntheticWorldBuilder(Format format) {
        this.format = format;
        this.dataVersion = switch (format) {
            case NUMERIC_1_12 -> DATA_VERSION_1_12_2;
            case PALETTED_1_15 -> DATA_VERSION_1_15_2;
        };
    }

    /** NUMERIC_1_12 专用：按数字 id/meta 放置方块。 */
    public LegacySyntheticWorldBuilder setBlock(int x, int y, int z, int id, int meta) {
        if (format != Format.NUMERIC_1_12) {
            throw new IllegalStateException("setBlock(id, meta) 仅用于 NUMERIC_1_12 格式");
        }
        numericBlocks.put(pack(x, y, z), (id << 4) | (meta & 15));
        return this;
    }

    /** PALETTED_1_15 专用：按扁平化状态串放置方块。 */
    public LegacySyntheticWorldBuilder setBlock(int x, int y, int z, String blockState) {
        if (format != Format.PALETTED_1_15) {
            throw new IllegalStateException("setBlock(String) 仅用于 PALETTED_1_15 格式");
        }
        stateBlocks.put(pack(x, y, z), blockState);
        return this;
    }

    /** 覆盖某区块的群系数据（数值格式 256 列 / 调色板格式 1024 quart）。 */
    public LegacySyntheticWorldBuilder setBiomes(ChunkPos chunk, byte[] columnBiomeIds) {
        columnBiomes.put(chunk, columnBiomeIds);
        return this;
    }

    public LegacySyntheticWorldBuilder setBiomes(ChunkPos chunk, int[] quartBiomeIds) {
        quartBiomes.put(chunk, quartBiomeIds);
        return this;
    }

    public void write(Path worldDir) {
        try {
            Files.createDirectories(worldDir);
            writeLevelDat(worldDir);
        } catch (IOException e) {
            throw new UncheckedIOException("写 level.dat 失败", e);
        }

        Map<Long, ?> blocks = format == Format.NUMERIC_1_12 ? numericBlocks : stateBlocks;
        Map<RegionPos, Map<ChunkPos, List<Long>>> byRegion = new LinkedHashMap<>();
        for (Long key : blocks.keySet()) {
            ChunkPos chunk = new ChunkPos(unpackX(key) >> 4, unpackZ(key) >> 4);
            byRegion.computeIfAbsent(chunk.toRegionPos(), r -> new TreeMap<>((a, b) -> {
                        int cmp = Integer.compare(a.x(), b.x());
                        return cmp != 0 ? cmp : Integer.compare(a.z(), b.z());
                    }))
                    .computeIfAbsent(chunk, c -> new ArrayList<>())
                    .add(key);
        }

        for (Map.Entry<RegionPos, Map<ChunkPos, List<Long>>> region : byRegion.entrySet()) {
            AnvilRegionWriter writer = new AnvilRegionWriter();
            for (Map.Entry<ChunkPos, List<Long>> chunk : region.getValue().entrySet()) {
                writer.putChunk(chunk.getKey(), buildChunkNbt(chunk.getKey(), chunk.getValue()));
            }
            writer.write(worldDir.resolve("region"), region.getKey());
        }
    }

    private CompoundTag buildChunkNbt(ChunkPos pos, List<Long> keys) {
        Map<Integer, List<Long>> bySection = new TreeMap<>();
        for (Long key : keys) {
            bySection.computeIfAbsent(unpackY(key) >> 4, s -> new ArrayList<>()).add(key);
        }

        List<Tag> sections = new ArrayList<>();
        for (Map.Entry<Integer, List<Long>> section : bySection.entrySet()) {
            sections.add(buildSectionNbt(pos, section.getKey(), section.getValue()));
        }

        Map<String, Tag> level = new LinkedHashMap<>();
        level.put("xPos", new IntTag(pos.x()));
        level.put("zPos", new IntTag(pos.z()));
        level.put("Sections", new ListTag(Tag.COMPOUND, sections));
        if (format == Format.NUMERIC_1_12) {
            level.put("Biomes", new ByteArrayTag(
                    columnBiomes.getOrDefault(pos, defaultColumnBiomes())));
        } else {
            level.put("Biomes", new IntArrayTag(
                    quartBiomes.getOrDefault(pos, defaultQuartBiomes())));
        }

        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("DataVersion", new IntTag(dataVersion));
        root.put("Level", new CompoundTag(level));
        return new CompoundTag(root);
    }

    private CompoundTag buildSectionNbt(ChunkPos pos, int sectionY, List<Long> keys) {
        Map<String, Tag> section = new LinkedHashMap<>();
        section.put("Y", new ByteTag((byte) (int) sectionY));
        if (format == Format.NUMERIC_1_12) {
            buildNumericSection(section, pos, sectionY, keys);
        } else {
            buildPalettedSection(section, pos, sectionY, keys);
        }
        // 天空光打满，方块光为空
        byte[] skyLight = new byte[2048];
        Arrays.fill(skyLight, (byte) 0xff);
        section.put("SkyLight", new ByteArrayTag(skyLight));
        section.put("BlockLight", new ByteArrayTag(new byte[2048]));
        return new CompoundTag(section);
    }

    private void buildNumericSection(Map<String, Tag> section, ChunkPos pos, int sectionY, List<Long> keys) {
        byte[] blocks = new byte[4096];
        byte[] add = new byte[2048];
        byte[] data = new byte[2048];
        boolean anyAdd = false;
        for (Long key : keys) {
            int encoded = numericBlocks.get(key);
            int id = encoded >> 4, meta = encoded & 15;
            int localX = unpackX(key) - (pos.x() << 4);
            int localY = unpackY(key) - (sectionY << 4);
            int localZ = unpackZ(key) - (pos.z() << 4);
            int i = localY * 256 + localZ * 16 + localX;
            blocks[i] = (byte) (id & 0xFF);
            if (id > 255) {
                setNibble(add, i, (id >> 8) & 0xF);
                anyAdd = true;
            }
            setNibble(data, i, meta);
        }
        section.put("Blocks", new ByteArrayTag(blocks));
        if (anyAdd) {
            section.put("Add", new ByteArrayTag(add));
        }
        section.put("Data", new ByteArrayTag(data));
    }

    private void buildPalettedSection(Map<String, Tag> section, ChunkPos pos, int sectionY, List<Long> keys) {
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        paletteIndex.put("minecraft:air", 0);

        int[] indices = new int[4096];
        for (Long key : keys) {
            int localX = unpackX(key) - (pos.x() << 4);
            int localY = unpackY(key) - (sectionY << 4);
            int localZ = unpackZ(key) - (pos.z() << 4);
            String state = stateBlocks.get(key);
            Integer idx = paletteIndex.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            indices[localY * 256 + localZ * 16 + localX] = idx;
        }

        List<Tag> paletteTags = new ArrayList<>();
        for (String state : palette) {
            BlockStateSpec spec = BlockStateSpec.parse(state);
            Map<String, Tag> entry = new LinkedHashMap<>();
            entry.put("Name", new StringTag(spec.block().toString()));
            if (!spec.properties().isEmpty()) {
                Map<String, Tag> props = new LinkedHashMap<>();
                spec.properties().forEach((k, v) -> props.put(k, new StringTag(v)));
                entry.put("Properties", new CompoundTag(props));
            }
            paletteTags.add(new CompoundTag(entry));
        }
        section.put("Palette", new ListTag(Tag.COMPOUND, paletteTags));

        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        section.put("BlockStates", new LongArrayTag(packCrossLong(indices, bits)));
    }

    /** 1.13–1.15 旧打包：值按位流连续排布，允许跨 long 边界。 */
    static long[] packCrossLong(int[] indices, int bits) {
        long[] data = new long[(indices.length * bits + 63) / 64];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < indices.length; i++) {
            long bitIndex = (long) i * bits;
            int longIndex = (int) (bitIndex >> 6);
            int bitOffset = (int) (bitIndex & 63);
            long value = indices[i] & mask;
            data[longIndex] |= value << bitOffset;
            int overflow = bitOffset + bits - 64;
            if (overflow > 0) {
                data[longIndex + 1] |= value >>> (bits - overflow);
            }
        }
        return data;
    }

    private static void setNibble(byte[] array, int index, int value) {
        int packed = array[index >> 1] & 0xFF;
        if ((index & 1) == 0) {
            packed = (packed & 0xF0) | (value & 0x0F);
        } else {
            packed = (packed & 0x0F) | ((value & 0x0F) << 4);
        }
        array[index >> 1] = (byte) packed;
    }

    private static byte[] defaultColumnBiomes() {
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 1); // legacy plains
        return biomes;
    }

    private static int[] defaultQuartBiomes() {
        int[] biomes = new int[1024];
        Arrays.fill(biomes, 1); // legacy plains
        return biomes;
    }

    private void writeLevelDat(Path worldDir) throws IOException {
        String versionName = switch (format) {
            case NUMERIC_1_12 -> "1.12.2";
            case PALETTED_1_15 -> "1.15.2";
        };
        Map<String, Tag> version = new LinkedHashMap<>();
        version.put("Name", new StringTag(versionName));
        version.put("Id", new IntTag(dataVersion));

        Map<String, Tag> data = new LinkedHashMap<>();
        data.put("DataVersion", new IntTag(dataVersion));
        data.put("Version", new CompoundTag(version));

        byte[] bytes = new NbtWriter().writeNamedRoot("", new CompoundTag(Map.of("Data", new CompoundTag(data))), true);
        Files.write(worldDir.resolve("level.dat"), bytes);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfffL);
    }

    private static int unpackX(long key) {
        return signExtend((int) (key >> 38), 26);
    }

    private static int unpackZ(long key) {
        return signExtend((int) (key >> 12), 26);
    }

    private static int unpackY(long key) {
        return signExtend((int) key, 12);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }
}
