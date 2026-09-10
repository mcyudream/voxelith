package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.LevelInfo;
import online.yudream.voxelith.world.domain.world.PalettedContainer;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.infrastructure.nbt.NbtReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 现代格式（1.18+，1.20.1 验证）Anvil 世界读取器。
 * 区块布局：根标签直接含 sections[]；section 含 Y / block_states{palette,data} / biomes / BlockLight / SkyLight。
 */
public final class AnvilWorldReader implements WorldReader {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final NbtReader nbtReader = new NbtReader();

    @Override
    public LevelInfo readLevelInfo(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            throw new IllegalArgumentException("level.dat 不存在: " + levelDat);
        }
        try {
            CompoundTag root = nbtReader.readNamedRootAuto(Files.readAllBytes(levelDat));
            CompoundTag data = root.getCompound("Data");
            int dataVersion = data.getInt("DataVersion");
            String versionName = data.getCompound("Version").getStringOrDefault("Name", "unknown");
            return new LevelInfo(versionName, dataVersion);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 level.dat 失败: " + levelDat, e);
        }
    }

    @Override
    public Map<RegionPos, List<ChunkRef>> scanRegions(Path dimensionDir) {
        Path regionDir = dimensionDir.resolve("region");
        Map<RegionPos, List<ChunkRef>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(regionDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                RegionPos region = new RegionPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                try (AnvilRegionReader reader = new AnvilRegionReader(file)) {
                    List<ChunkRef> chunks = reader.listChunks().stream()
                            .map(e -> new ChunkRef(
                                    AnvilRegionReader.chunkPos(region.x(), region.z(), e.localX(), e.localZ()),
                                    e.timestampSeconds()))
                            .toList();
                    if (!chunks.isEmpty()) {
                        result.put(region, chunks);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描 region 目录失败: " + regionDir, e);
        }
        return result;
    }

    @Override
    public List<ChunkData> readRegion(Path dimensionDir, RegionPos region) {
        Path file = dimensionDir.resolve("region").resolve(region.fileName());
        List<ChunkData> chunks = new ArrayList<>();
        try (AnvilRegionReader reader = new AnvilRegionReader(file)) {
            for (AnvilRegionReader.ChunkEntry entry : reader.listChunks()) {
                reader.readChunkPayload(entry.localX(), entry.localZ())
                        .map(payload -> parseChunk(
                                AnvilRegionReader.chunkPos(region.x(), region.z(), entry.localX(), entry.localZ()),
                                payload))
                        .ifPresent(chunks::add);
            }
        }
        return chunks;
    }

    @Override
    public Optional<ChunkData> readChunk(Path dimensionDir, ChunkPos pos) {
        RegionPos region = pos.toRegionPos();
        Path file = dimensionDir.resolve("region").resolve(region.fileName());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (AnvilRegionReader reader = new AnvilRegionReader(file)) {
            return reader.readChunkPayload(pos.localX(), pos.localZ())
                    .map(payload -> parseChunk(pos, payload));
        }
    }

    private ChunkData parseChunk(ChunkPos pos, byte[] payload) {
        CompoundTag root = nbtReader.readNamedRoot(payload, NbtReader.Compression.NONE);
        int dataVersion = root.contains("DataVersion") ? root.getInt("DataVersion") : 0;
        if (!root.contains("sections")) {
            throw new IllegalStateException(
                    "区块缺少 sections（pre-1.18 Level 布局），需要 legacy 适配器: " + pos);
        }

        List<ChunkSection> sections = new ArrayList<>();
        for (Tag tag : root.getList("sections").value()) {
            CompoundTag section = (CompoundTag) tag;
            sections.add(parseSection(section));
        }
        return new ChunkData(pos, sections, dataVersion);
    }

    private ChunkSection parseSection(CompoundTag section) {
        int y = section.getByte("Y");

        PalettedContainer<BlockStateSpec> blockStates = null;
        if (section.contains("block_states")) {
            CompoundTag blockStatesTag = section.getCompound("block_states");
            List<BlockStateSpec> palette = new ArrayList<>();
            for (Tag tag : blockStatesTag.getList("palette").value()) {
                CompoundTag entry = (CompoundTag) tag;
                Map<String, String> props = new LinkedHashMap<>();
                if (entry.contains("Properties")) {
                    CompoundTag properties = entry.getCompound("Properties");
                    for (String key : properties.keys()) {
                        props.put(key, properties.getString(key));
                    }
                }
                palette.add(new BlockStateSpec(Identifier.parse(entry.getString("Name")), props));
            }
            long[] data = blockStatesTag.contains("data") ? blockStatesTag.getLongArray("data") : null;
            blockStates = new PalettedContainer<>(palette, data, 4, 4096);
        }

        PalettedContainer<String> biomes = null;
        if (section.contains("biomes")) {
            CompoundTag biomesTag = section.getCompound("biomes");
            List<String> palette = new ArrayList<>();
            for (Tag tag : biomesTag.getList("palette").value()) {
                palette.add(((StringTag) tag).value());
            }
            long[] data = biomesTag.contains("data") ? biomesTag.getLongArray("data") : null;
            biomes = new PalettedContainer<>(palette, data, 1, 64);
        }

        byte[] skyLight = section.contains("SkyLight") ? section.getByteArray("SkyLight") : null;
        byte[] blockLight = section.contains("BlockLight") ? section.getByteArray("BlockLight") : null;

        return new ChunkSection(y, blockStates, biomes, skyLight, blockLight);
    }
}
