package online.yudream.voxelith.sharedkernel.vo;

/**
 * Minecraft 版本标识。dataVersion 为存档 NBT 中的 DataVersion，
 * 是选择区块解析适配器的权威依据（比版本字符串可靠）。
 */
public record McVersion(String name, int dataVersion) implements Comparable<McVersion> {

    /** 1.13 扁平化分界：小于此 dataVersion 的世界为 legacy 数字 ID/meta 格式。 */
    public static final int FLATTENING_DATA_VERSION = 1519;

    public boolean isLegacy() {
        return dataVersion < FLATTENING_DATA_VERSION;
    }

    @Override
    public int compareTo(McVersion other) {
        return Integer.compare(dataVersion, other.dataVersion);
    }
}
