package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.PalettedContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现代格式（1.18+，1.20.1 验证）区块解析器。
 * 布局：根标签直接含 sections[]；section 含 Y / block_states{palette,data} / biomes / BlockLight / SkyLight。
 */
public final class ModernChunkParser implements ChunkPayloadParser {

    @Override
    public boolean supports(CompoundTag chunkRoot) {
        return chunkRoot.contains("sections");
    }

    @Override
    public ChunkData parse(ChunkPos pos, CompoundTag root) {
        int dataVersion = root.contains("DataVersion") ? root.getInt("DataVersion") : 0;

        List<ChunkSection> sections = new ArrayList<>();
        for (Tag tag : root.getList("sections").value()) {
            sections.add(parseSection((CompoundTag) tag));
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
