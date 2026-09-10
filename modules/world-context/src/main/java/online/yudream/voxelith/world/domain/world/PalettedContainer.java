package online.yudream.voxelith.world.domain.world;

import java.util.List;

/**
 * 调色板容器。
 * 打包格式有两种：
 * <ul>
 *   <li>现代（1.16+，DataVersion ≥ 2529）：值不跨 long 边界；</li>
 *   <li>旧版（1.13–1.15）：值紧密排列，允许跨 long 边界。</li>
 * </ul>
 * 支持 indirect（≤8 bit）与单值（0 bit）形态；biomes 同理（≤3 bit 间接）。
 */
public final class PalettedContainer<T> {

    private final List<T> palette;
    private final long[] data;
    private final int bitsPerEntry;
    private final int entryCount;
    private final boolean crossLong;

    /**
     * @param palette    调色板
     * @param data       打包数据（调色板只有一个条目时可为空）
     * @param minBits    最小位宽（方块 4，群系 1）
     * @param entryCount 条目总数（方块 section 4096，群系 64）
     */
    public PalettedContainer(List<T> palette, long[] data, int minBits, int entryCount) {
        this(palette, data, minBits, entryCount, false);
    }

    /**
     * @param crossLong 旧版打包（1.13–1.15）：值允许跨 long 边界
     */
    public PalettedContainer(List<T> palette, long[] data, int minBits, int entryCount, boolean crossLong) {
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("调色板不能为空");
        }
        this.palette = List.copyOf(palette);
        this.entryCount = entryCount;
        this.crossLong = crossLong;
        if (palette.size() == 1) {
            this.bitsPerEntry = 0;
            this.data = new long[0];
        } else {
            int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            this.bitsPerEntry = bits;
            this.data = data == null ? new long[0] : data;
            int expectedLongs = crossLong
                    ? (int) (((long) entryCount * bits + 63) / 64)
                    : (entryCount + (64 / bits) - 1) / (64 / bits);
            if (this.data.length < expectedLongs) {
                throw new IllegalArgumentException(
                        "打包数据长度不足: 需要 " + expectedLongs + " 个 long，实际 " + this.data.length);
            }
        }
    }

    /** 由索引数组构建（现代打包）：供 legacy 适配器把解码结果重打包为统一容器。 */
    public static <T> PalettedContainer<T> fromIndices(List<T> palette, int[] indices, int minBits) {
        int bits = palette.size() == 1 ? 0 : Math.max(minBits, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        if (bits == 0) {
            return new PalettedContainer<>(List.of(palette.getFirst()), new long[0], minBits, indices.length);
        }
        int perLong = 64 / bits;
        long[] data = new long[(indices.length + perLong - 1) / perLong];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < indices.length; i++) {
            int longIndex = i / perLong;
            int slot = i % perLong;
            data[longIndex] |= ((long) indices[i] & mask) << (slot * bits);
        }
        return new PalettedContainer<>(palette, data, minBits, indices.length);
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
        return palette.get(paletteIndexAt(index));
    }

    private int paletteIndexAt(int index) {
        long mask = (1L << bitsPerEntry) - 1;
        if (crossLong) {
            long bitOffset = (long) index * bitsPerEntry;
            int longIndex = (int) (bitOffset >>> 6);
            int bitInLong = (int) (bitOffset & 63);
            int value = (int) ((data[longIndex] >>> bitInLong) & mask);
            int overflow = bitInLong + bitsPerEntry - 64;
            if (overflow > 0) {
                value |= (int) ((data[longIndex + 1] << (bitsPerEntry - overflow)) & mask);
            }
            return value;
        }
        int perLong = 64 / bitsPerEntry;
        int longIndex = index / perLong;
        int slot = index % perLong;
        return (int) ((data[longIndex] >>> (slot * bitsPerEntry)) & mask);
    }

    public int entryCount() {
        return entryCount;
    }
}
