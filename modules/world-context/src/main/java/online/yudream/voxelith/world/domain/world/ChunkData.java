package online.yudream.voxelith.world.domain.world;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一个区块的解析结果。
 */
public record ChunkData(ChunkPos pos, List<ChunkSection> sections, int dataVersion) {

    public Optional<ChunkSection> sectionAt(int sectionY) {
        return sections.stream().filter(s -> s.y() == sectionY).findFirst();
    }

    /** 按 y 升序的截面列表。 */
    public List<ChunkSection> orderedSections() {
        return sections.stream().sorted(Comparator.comparingInt(ChunkSection::y)).toList();
    }
}
