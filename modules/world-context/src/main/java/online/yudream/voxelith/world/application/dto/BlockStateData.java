package online.yudream.voxelith.world.application.dto;

import java.util.Map;

/**
 * 方块状态的跨上下文传输形态（烘焙链路消费）。
 *
 * @param block      方块 id（"minecraft:stone" 形式）
 * @param properties 状态属性表
 */
public record BlockStateData(String block, Map<String, String> properties) {

    public boolean isAir() {
        return "minecraft:air".equals(block)
                || "minecraft:cave_air".equals(block)
                || "minecraft:void_air".equals(block);
    }
}
