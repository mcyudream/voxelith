package online.yudream.voxelith.world.domain.world;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 扁平化后的方块状态描述："minecraft:oak_log[axis=y]" → 标识 + 属性表。
 */
public record BlockStateSpec(Identifier block, Map<String, String> properties) {

    /** 解析调色板条目，如 "minecraft:stone" 或 "minecraft:oak_fence[north=true,waterlogged=false]"。 */
    public static BlockStateSpec parse(String raw) {
        int bracket = raw.indexOf('[');
        if (bracket < 0) {
            return new BlockStateSpec(Identifier.parse(raw), Map.of());
        }
        Identifier block = Identifier.parse(raw.substring(0, bracket));
        Map<String, String> props = new LinkedHashMap<>();
        String inner = raw.substring(bracket + 1, raw.length() - 1);
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return new BlockStateSpec(block, props);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return block.toString();
        }
        return block + "[" + properties.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(",")) + "]";
    }
}
