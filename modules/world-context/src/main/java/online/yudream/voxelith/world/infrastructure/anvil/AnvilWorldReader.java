package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.LevelInfo;
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
 * Anvil 世界读取器（全版本）。
 * region 容器各版本一致；区块 NBT 按形状分派给 {@link ChunkPayloadParser} 注册表：
 * 现代（1.18+）→ 1.13–1.17 调色板 legacy → 1.12- 数字 ID legacy。
 * 升级过的存档中不同区块可保持各自写入时的格式，按区块独立分派天然支持混合格式。
 */
public final class AnvilWorldReader implements WorldReader {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final NbtReader nbtReader = new NbtReader();
    private final List<ChunkPayloadParser> parsers;

    public AnvilWorldReader() {
        this(List.of(new ModernChunkParser(), new PalettedLegacyChunkParser(), new NumericLegacyChunkParser()));
    }

    /** 自定义解析器注册表（SPI 扩展点）：按序匹配，首个 supports 命中者解析。 */
    public AnvilWorldReader(List<ChunkPayloadParser> parsers) {
        if (parsers.isEmpty()) {
            throw new IllegalArgumentException("解析器注册表不能为空");
        }
        this.parsers = List.copyOf(parsers);
    }

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
        for (ChunkPayloadParser parser : parsers) {
            if (parser.supports(root)) {
                return parser.parse(pos, root);
            }
        }
        throw new IllegalStateException("未知区块 NBT 布局，无匹配解析器: " + pos);
    }
}
