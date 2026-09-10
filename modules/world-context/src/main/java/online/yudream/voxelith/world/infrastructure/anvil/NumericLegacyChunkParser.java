package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.PalettedContainer;
import online.yudream.voxelith.world.infrastructure.legacy.LegacyBiomeIds;
import online.yudream.voxelith.world.infrastructure.legacy.LegacyFlatteningTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.12 及更早数字 ID/meta 区块解析器。
 * 布局：根含 Level 复合标签；Level.Sections[] 含 Y / Blocks(4096 字节 ID 低 8 位)
 * / Add(可选 2048 nibble，ID 高 4 位) / Data(2048 nibble meta) / BlockLight / SkyLight；
 * Level.Biomes 为 byte[256] 列群系。
 * 数字 ID/meta 经 {@link LegacyFlatteningTable} 映射为扁平化方块状态后重打包为现代容器，
 * 下游烘焙链路无需感知 legacy 差异。
 */
public final class NumericLegacyChunkParser implements ChunkPayloadParser {

    private final LegacyFlatteningTable flatteningTable;

    public NumericLegacyChunkParser() {
        this(LegacyFlatteningTable.shared());
    }

    public NumericLegacyChunkParser(LegacyFlatteningTable flatteningTable) {
        this.flatteningTable = flatteningTable;
    }

    @Override
    public boolean supports(CompoundTag chunkRoot) {
        return chunkRoot.contains("Level");
    }

    @Override
    public ChunkData parse(ChunkPos pos, CompoundTag root) {
        int dataVersion = root.getIntOrDefault("DataVersion", 0);
        CompoundTag level = root.getCompound("Level");

        byte[] columnBiomes = level.contains("Biomes") && level.get("Biomes").orElseThrow()
                instanceof online.yudream.voxelith.world.domain.nbt.ByteArrayTag
                ? level.getByteArray("Biomes") : null;

        List<ChunkSection> sections = new ArrayList<>();
        if (level.contains("Sections")) {
            for (Tag tag : level.getList("Sections").value()) {
                sections.add(parseSection((CompoundTag) tag, columnBiomes));
            }
        }
        return new ChunkData(pos, sections, dataVersion);
    }

    private ChunkSection parseSection(CompoundTag section, byte[] columnBiomes) {
        int y = section.getByte("Y");

        PalettedContainer<BlockStateSpec> blockStates = null;
        if (section.contains("Blocks")) {
            byte[] blocks = section.getByteArray("Blocks");
            byte[] add = section.contains("Add") ? section.getByteArray("Add") : null;
            byte[] data = section.contains("Data") ? section.getByteArray("Data") : null;

            int[] indices = new int[4096];
            List<BlockStateSpec> palette = new ArrayList<>();
            Map<BlockStateSpec, Integer> paletteIndex = new LinkedHashMap<>();
            for (int i = 0; i < 4096; i++) {
                int id = (blocks[i] & 0xFF) | (nibble(add, i) << 8);
                int meta = nibble(data, i);
                BlockStateSpec spec = flatteningTable.map(id, meta);
                int idx = paletteIndex.computeIfAbsent(spec, s -> {
                    palette.add(s);
                    return palette.size() - 1;
                });
                indices[i] = idx;
            }
            blockStates = PalettedContainer.fromIndices(palette, indices, 4);
        }

        PalettedContainer<String> biomes = buildBiomes(columnBiomes);

        byte[] skyLight = section.contains("SkyLight") ? section.getByteArray("SkyLight") : null;
        byte[] blockLight = section.contains("BlockLight") ? section.getByteArray("BlockLight") : null;

        return new ChunkSection(y, blockStates, biomes, skyLight, blockLight);
    }

    /** 列群系（byte[256]）重排为 4×4×4 容器：y 维度全部填列值（1.12 群系仅 2D）。 */
    static PalettedContainer<String> buildBiomes(byte[] columnBiomes) {
        if (columnBiomes == null || columnBiomes.length < 256) {
            return null;
        }
        int[] indices = new int[64];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    String name = LegacyBiomeIds.nameOf(
                            columnBiomes[(qz * 4 + 2) * 16 + (qx * 4 + 2)] & 0xFF);
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

    /** legacy nibble 数组：偶数索引取低 4 位。数组为空/长度不足时返回 0。 */
    static int nibble(byte[] array, int index) {
        if (array == null || array.length != 2048) {
            return 0;
        }
        int packed = array[index >> 1] & 0xFF;
        return (index & 1) == 0 ? packed & 0x0F : packed >> 4;
    }
}
