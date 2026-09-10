package online.yudream.voxelith.resource.domain.biome;

import java.util.Optional;

/**
 * 群系数据源端口：群系定义与 colormap 像素的读取抽象（实现位于 infrastructure 层）。
 */
public interface BiomeSource {

    /** 群系效果参数；群系不存在时为 empty（调用方兜底 BiomeEffects.DEFAULT）。 */
    Optional<BiomeEffects> effects(String biomeId);

    /**
     * colormap 像素（assets/minecraft/textures/colormap/{name}.png，行主序 RGB，256×256）。
     * 缺失时为 empty（调用方退回固定色）。
     */
    Optional<int[]> colormap(String name);
}
