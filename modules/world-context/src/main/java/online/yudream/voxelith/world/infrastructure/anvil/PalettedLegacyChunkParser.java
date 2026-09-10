package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.PalettedContainer;
import online.yudream.voxelith.world.infrastructure.legacy.LegacyBiomeIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.13–1.17 调色板 legacy 区块解析器。
 * 布局：根含 Level 复合标签；Level.Sections[] 含 Y / Palette / BlockStates / BlockLight / SkyLight；
 * Level.Biomes 为 int 数组（1.13–1.14 为 256 列群系，1.15+ 为 1024 个 4×4×4 quart 群系）。
 * 打包差异：DataVersion &lt; 2529（1.16 20w17a 前）值允许跨 long 边界。
 */
public final class PalettedLegacyChunkParser implements ChunkPayloadParser {

    /** 1.16 打包格式变更分界（20w17a）。 */
    static final int NEW_PACKING_DATA_VERSION = 2529;

    @Override
    public boolean supports(CompoundTag chunkRoot) {
        return chunkRoot.contains("Level")
                && chunkRoot.getIntOrDefault("DataVersion", 0) >= 1519;
    }

    @Override
    public ChunkData parse(ChunkPos pos, CompoundTag root) {
        int dataVersion = root.getIntOrDefault("DataVersion", 0);
        boolean crossLong = dataVersion < NEW_PACKING_DATA_VERSION;
        CompoundTag level = root.getCompound("Level");

        int[] columnBiomes = null;
        int[] quartBiomes = null;
        if (level.contains("Biomes") && level.get("Biomes").orElseThrow() instanceof online.yudream.voxelith.world.domain.nbt.IntArrayTag) {
            int[] biomes = level.getIntArray("Biomes");
            if (biomes.length >= 1024) {
                quartBiomes = biomes;
            } else if (biomes.length >= 256) {
                columnBiomes = biomes;
            }
        }

        List<ChunkSection> sections = new ArrayList<>();
        if (level.contains("Sections")) {
            for (Tag tag : level.getList("Sections").value()) {
                sections.add(parseSection((CompoundTag) tag, crossLong, columnBiomes, quartBiomes));
            }
        }
        return new ChunkData(pos, sections, dataVersion);
    }

    private ChunkSection parseSection(CompoundTag section, boolean crossLong,
                                      int[] columnBiomes, int[] quartBiomes) {
        int y = section.getByte("Y");

        PalettedContainer<BlockStateSpec> blockStates = null;
        if (section.contains("Palette")) {
            List<BlockStateSpec> palette = new ArrayList<>();
            for (Tag tag : section.getList("Palette").value()) {
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
            long[] data = section.contains("BlockStates") ? section.getLongArray("BlockStates") : null;
            blockStates = new PalettedContainer<>(palette, data, 4, 4096, crossLong);
        }

        PalettedContainer<String> biomes = buildBiomes(y, columnBiomes, quartBiomes);

        byte[] skyLight = section.contains("SkyLight") ? section.getByteArray("SkyLight") : null;
        byte[] blockLight = section.contains("BlockLight") ? section.getByteArray("BlockLight") : null;

        return new ChunkSection(y, blockStates, biomes, skyLight, blockLight);
    }

    /**
     * 把 chunk 级群系数组重排为 section 级 4×4×4 容器。
     * 1.13–1.14 只有列群系（256）：y 维度全部填列值；1.15+ 有 quart 群系（1024）：直接按全局 quart y 抽取。
     */
    static PalettedContainer<String> buildBiomes(int sectionY, int[] columnBiomes, int[] quartBiomes) {
        if (columnBiomes == null && quartBiomes == null) {
            return null;
        }
        int[] indices = new int[64];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    int biomeId;
                    if (quartBiomes != null) {
                        int globalQY = sectionY * 4 + qy;
                        biomeId = quartBiomes[globalQY * 16 + qz * 4 + qx];
                    } else {
                        biomeId = columnBiomes[(qz * 4 + 2) * 16 + (qx * 4 + 2)];
                    }
                    String name = LegacyBiomeIds.nameOf(biomeId);
                    int idx = paletteIndex.computeIfAbsent(name, n -> {
                        palette.add(n);
                        return palette.size() - 1;
                    });
                    indices[qy * 16 + qz * 4 + qx] = idx;
                }
            }
        }
        return PalettedContainer.fromIndices(palette, indices, 1);
    }
}
