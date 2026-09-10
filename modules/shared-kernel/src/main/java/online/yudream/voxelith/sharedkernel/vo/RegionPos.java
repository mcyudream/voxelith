package online.yudream.voxelith.sharedkernel.vo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Region 坐标（32×32 区块 = 512×512 方块），渲染任务分片与增量更新的最小调度单元。
 */
public record RegionPos(int x, int z) {

    private static final Pattern FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public String fileName() {
        return "r." + x + "." + z + ".mca";
    }

    public static Optional<RegionPos> parseFileName(String name) {
        Matcher matcher = FILE.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RegionPos(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    }
}
