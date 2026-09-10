package online.yudream.voxelith.world.domain.world;

import java.util.List;

/**
 * 调色板容器（1.16+ 打包格式：值不跨 long 边界）。
 * 支持 blocks 的 indirect（≤8 bit）与单值（0 bit）形态；biomes 同理（≤3 bit 间接）。
 */
public final class PalettedContainer<T> {

    private final List<T> palette;
    private final long[] data;
    private final int bitsPerEntry;
    private final int entryCount;

    /**
     * @param palette     调色板
     * @param data        打包数据（调色板只有一个条目时可为空）
     * @param minBits     最小位宽（方块 4，群系 1）
     * @param entryCount  条目总数（方块 section 4096，群系 64）
     */
    public PalettedContainer(List<T> palette, long[] data, int minBits, int entryCount) {
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("调色板不能为空");
        }
        this.palette = List.copyOf(palette);
        this.entryCount = entryCount;
        if (palette.size() == 1) {
            this.bitsPerEntry = 0;
            this.data = new long[0];
        } else {
            int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            this.bitsPerEntry = bits;
            this.data = data == null ? new long[0] : data;
            int expectedLongs = (int) (((long) entryCount * bits + 63) / 64);
            if (this.data.length < expectedLongs) {
                throw new IllegalArgumentException(
                        "打包数据长度不足: 需要 " + expectedLongs + " 个 long，实际 " + this.data.length);
            }
        }
    }

    public List<T> palette() {
        return palette;
    }

    public int bitsPerEntry() {
        return bitsPerEntry;
    }

    /** 取第 index 个条目（区块内序：y 主序 → z → x，即 index = y*256 + z*16 + x）。 */
    public T get(int index) {
        if (bitsPerEntry == 0) {
            return palette.getFirst();
        }
        int perLong = 64 / bitsPerEntry;
        int longIndex = index / perLong;
        int slot = index % perLong;
        long mask = (1L << bitsPerEntry) - 1;
        int paletteIndex = (int) ((data[longIndex] >>> (slot * bitsPerEntry)) & mask);
        return palette.get(paletteIndex);
    }

    public int entryCount() {
        return entryCount;
    }
}
