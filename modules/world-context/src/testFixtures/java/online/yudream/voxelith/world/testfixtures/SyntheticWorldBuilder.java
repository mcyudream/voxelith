package online.yudream.voxelith.world.testfixtures;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.ByteArrayTag;
import online.yudream.voxelith.world.domain.nbt.ByteTag;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 合成存档生成器：按游戏内真实 1.20.1 Anvil 布局生成可直接被读取链路消费的世界。
 * 用于 golden 测试与浏览器 demo 世界。
 *
 * <p>用法：builder.setBlock(x, y, z, "minecraft:stone") … builder.write(worldDir)。</p>
 */
public final class SyntheticWorldBuilder {

    public static final int DATA_VERSION_1_20_1 = 3465;

    /** 世界方块表：key = x,y,z 打包。 */
    private final Map<Long, String> blocks = new TreeMap<>();
    private final int dataVersion;

    public SyntheticWorldBuilder() {
        this(DATA_VERSION_1_20_1);
    }

    public SyntheticWorldBuilder(int dataVersion) {
        this.dataVersion = dataVersion;
    }

    public SyntheticWorldBuilder setBlock(int x, int y, int z, String blockState) {
        blocks.put(pack(x, y, z), blockState);
        return this;
    }

    /** 以 y0 为地表铺一块 superflat 区域：bedrock → stone → dirt → grass_block。 */
    public SyntheticWorldBuilder flatGround(int minX, int minZ, int maxX, int maxZ, int surfaceY) {
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                setBlock(x, 0, z, "minecraft:bedrock");
                for (int y = 1; y < surfaceY - 3; y++) {
                    setBlock(x, y, z, "minecraft:stone");
                }
                for (int y = Math.max(1, surfaceY - 3); y < surfaceY; y++) {
                    setBlock(x, y, z, "minecraft:dirt");
                }
                setBlock(x, surfaceY, z, "minecraft:grass_block[snowy=false]");
            }
        }
        return this;
    }

    /** 写存档：level.dat + region 文件。 */
    public void write(Path worldDir) {
        try {
            Files.createDirectories(worldDir);
            writeLevelDat(worldDir);
        } catch (IOException e) {
            throw new UncheckedIOException("写 level.dat 失败", e);
        }

        // 按 region → chunk 聚合
        Map<RegionPos, Map<ChunkPos, Map<Long, String>>> byRegion = new LinkedHashMap<>();
        for (Map.Entry<Long, String> entry : blocks.entrySet()) {
            long key = entry.getKey();
            int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
            ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
            byRegion.computeIfAbsent(chunk.toRegionPos(), r -> new TreeMap<>((a, b) -> {
                        int cmp = Integer.compare(a.x(), b.x());
                        return cmp != 0 ? cmp : Integer.compare(a.z(), b.z());
                    }))
                    .computeIfAbsent(chunk, c -> new TreeMap<>())
                    .put(key, entry.getValue());
        }

        for (Map.Entry<RegionPos, Map<ChunkPos, Map<Long, String>>> region : byRegion.entrySet()) {
            AnvilRegionWriter writer = new AnvilRegionWriter();
            for (Map.Entry<ChunkPos, Map<Long, String>> chunk : region.getValue().entrySet()) {
                writer.putChunk(chunk.getKey(), buildChunkNbt(chunk.getKey(), chunk.getValue()));
            }
            writer.write(worldDir.resolve("region"), region.getKey());
        }
    }

    private CompoundTag buildChunkNbt(ChunkPos pos, Map<Long, String> chunkBlocks) {
        // 按 section 分桶
        Map<Integer, Map<Long, String>> bySection = new TreeMap<>();
        for (Map.Entry<Long, String> entry : chunkBlocks.entrySet()) {
            int y = unpackY(entry.getKey());
            bySection.computeIfAbsent(y >> 4, s -> new TreeMap<>()).put(entry.getKey(), entry.getValue());
        }

        List<Tag> sections = new ArrayList<>();
        for (Map.Entry<Integer, Map<Long, String>> section : bySection.entrySet()) {
            sections.add(buildSectionNbt(pos, section.getKey(), section.getValue()));
        }

        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("DataVersion", new IntTag(dataVersion));
        root.put("xPos", new IntTag(pos.x()));
        root.put("zPos", new IntTag(pos.z()));
        root.put("sections", new ListTag(Tag.COMPOUND, sections));
        return new CompoundTag(root);
    }

    private CompoundTag buildSectionNbt(ChunkPos pos, int sectionY, Map<Long, String> sectionBlocks) {
        // 调色板：air 恒在索引 0
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        paletteIndex.put("minecraft:air", 0);

        int[] indices = new int[4096];
        for (Map.Entry<Long, String> entry : sectionBlocks.entrySet()) {
            int localX = unpackX(entry.getKey()) - (pos.x() << 4);
            int localY = unpackY(entry.getKey()) - (sectionY << 4);
            int localZ = unpackZ(entry.getKey()) - (pos.z() << 4);
            String state = entry.getValue();
            Integer idx = paletteIndex.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            indices[localY * 256 + localZ * 16 + localX] = idx;
        }

        int bits = palette.size() == 1 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));

        Map<String, Tag> blockStates = new LinkedHashMap<>();
        List<Tag> paletteTags = new ArrayList<>();
        for (String state : palette) {
            // 与真实存档一致：Name 只放方块 id，属性写入 Properties 复合标签
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
        blockStates.put("palette", new ListTag(Tag.COMPOUND, paletteTags));
        if (bits > 0) {
            blockStates.put("data", new LongArrayTag(pack(indices, bits)));
        }

        Map<String, Tag> section = new LinkedHashMap<>();
        section.put("Y", new ByteTag((byte) (int) sectionY));
        section.put("block_states", new CompoundTag(blockStates));
        section.put("biomes", new CompoundTag(Map.of(
                "palette", new ListTag(Tag.STRING, List.of(new StringTag("minecraft:plains"))))));
        // 天空光打满（合成世界默认白昼），方块光为空（未放置光源）
        byte[] skyLight = new byte[2048];
        java.util.Arrays.fill(skyLight, (byte) 0xff);
        section.put("SkyLight", new ByteArrayTag(skyLight));
        return new CompoundTag(section);
    }

    private static long[] pack(int[] indices, int bits) {
        int perLong = 64 / bits;
        long[] data = new long[(indices.length + perLong - 1) / perLong];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < indices.length; i++) {
            int longIndex = i / perLong;
            int slot = i % perLong;
            data[longIndex] |= (indices[i] & mask) << (slot * bits);
        }
        return data;
    }

    private void writeLevelDat(Path worldDir) throws IOException {
        Map<String, Tag> version = new LinkedHashMap<>();
        version.put("Name", new StringTag("1.20.1"));
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
