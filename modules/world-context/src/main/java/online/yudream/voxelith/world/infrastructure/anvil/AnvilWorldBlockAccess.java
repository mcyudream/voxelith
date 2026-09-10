package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.application.dto.BlockStateData;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.WorldReader;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Anvil 读取器的随机访问实现：按区块 LRU 缓存，供烘焙链路高频邻居查询。
 */
public final class AnvilWorldBlockAccess implements WorldBlockAccess {

    private static final int CHUNK_CACHE_CAPACITY = 2048;

    private final WorldReader reader;
    private final Path dimensionDir;

    /**
     * 区块缓存：ConcurrentHashMap 无锁读（烘焙链路高度并行，全局锁会把多核压回单核）；
     * 超容量时批量淘汰约一半（近似 LRU 不需要精确，读取热点集中）。
     */
    private final Map<ChunkPos, Optional<ChunkData>> chunkCache = new ConcurrentHashMap<>();

    public AnvilWorldBlockAccess(WorldReader reader, Path dimensionDir) {
        this.reader = reader;
        this.dimensionDir = dimensionDir;
    }

    @Override
    public Optional<BlockStateData> blockStateAt(int x, int y, int z) {
        return sectionAt(x, y, z).flatMap(section -> {
            BlockStateSpec spec = section.blockStateAt(x & 15, y & 15, z & 15);
            return Optional.of(new BlockStateData(spec.block().toString(), spec.properties()));
        });
    }

    @Override
    public int skyLightAt(int x, int y, int z) {
        return sectionAt(x, y, z)
                .map(section -> nibble(section.skyLight(), (y & 15) * 256 + (z & 15) * 16 + (x & 15)))
                .orElse(0);
    }

    @Override
    public int blockLightAt(int x, int y, int z) {
        return sectionAt(x, y, z)
                .map(section -> nibble(section.blockLight(), (y & 15) * 256 + (z & 15) * 16 + (x & 15)))
                .orElse(0);
    }

    @Override
    public int[] sectionYs(ChunkPos chunk) {
        return chunk(chunk)
                .map(data -> data.orderedSections().stream().mapToInt(ChunkSection::y).toArray())
                .orElse(new int[0]);
    }

    @Override
    public void invalidateRegion(RegionPos region) {
        chunkCache.keySet().removeIf(pos -> pos.toRegionPos().equals(region));
    }

    @Override
    public Optional<String> biomeAt(int x, int y, int z) {
        return chunk(new ChunkPos(x >> 4, z >> 4))
                .flatMap(data -> data.sectionAt(y >> 4))
                .filter(section -> section.biomes() != null)
                // 群系容器 4×4×4（quart 坐标），y 主序 → z → x，与方块容器同序
                .map(section -> section.biomes().get(
                        ((y & 15) >> 2) * 16 + ((z & 15) >> 2) * 4 + ((x & 15) >> 2)));
    }

    private Optional<ChunkSection> sectionAt(int x, int y, int z) {
        return chunk(new ChunkPos(x >> 4, z >> 4))
                .flatMap(data -> data.sectionAt(y >> 4))
                .filter(section -> section.blockStates() != null);
    }

    private Optional<ChunkData> chunk(ChunkPos pos) {
        Optional<ChunkData> cached = chunkCache.get(pos);
        if (cached != null) {
            return cached;
        }
        Optional<ChunkData> loaded = chunkCache.computeIfAbsent(pos,
                p -> reader.readChunk(dimensionDir, p));
        if (chunkCache.size() > CHUNK_CACHE_CAPACITY) {
            evictHalf();
        }
        return loaded;
    }

    /** 批量淘汰：移除约一半条目（迭代器序，非精确 LRU，热点集中于活动窗口足够近似）。 */
    private void evictHalf() {
        Iterator<ChunkPos> it = chunkCache.keySet().iterator();
        int toRemove = CHUNK_CACHE_CAPACITY / 2;
        while (it.hasNext() && toRemove-- > 0) {
            it.next();
            it.remove();
        }
    }

    private static int nibble(byte[] array, int index) {
        if (array == null || array.length != 2048) {
            return 0;
        }
        int packed = array[index >> 1] & 0xFF;
        return (index & 1) == 0 ? packed & 0x0F : packed >> 4;
    }
}
