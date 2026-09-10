package online.yudream.voxelith.runtime.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * headless 冒烟验证，复刻原版 main 的启动前序：
 * <ol>
 * <li>DetectedVersion.tryDetectVersion()（class_3797.method_16672）→ SharedConstants.setVersion
 * （class_155.method_34872，参数类型 WorldVersion=class_6489）；</li>
 * <li>Bootstrap.bootStrap()（class_2966.method_12851，注册 blocks/items/DataFixer 等）；</li>
 * <li>强制初始化 Blocks（class_2246），统计 Block（class_2248）类型静态字段中非 null 数量。</li>
 * </ol>
 * 注册表被填满即证明 remap + 类加载 + bootstrap + 注册链路在无头环境可用。
 *
 * <p>intermediary 名按 1.20.1 固定（ADR 0001 首个适配目标）；后续版本适配时按版本映射表替换。</p>
 */
public final class MinecraftBlockSmoke {

    /** 1.20.1 原版方块数约 1000+，取保守下限防止误判。 */
    public static final int MIN_EXPECTED_BLOCKS = 900;

    private static final String BOOTSTRAP_CLASS = "net.minecraft.class_2966";
    private static final String BOOTSTRAP_METHOD = "method_12851";

    public static int countRegisteredBlocks(ClassLoader gameClassLoader) throws Exception {
        Class<?> worldVersionClass = Class.forName("net.minecraft.class_6489", true, gameClassLoader);
        Object version = Class.forName("net.minecraft.class_3797", true, gameClassLoader)
                .getMethod("method_16672")
                .invoke(null);
        Class.forName("net.minecraft.class_155", true, gameClassLoader)
                .getMethod("method_34872", worldVersionClass)
                .invoke(null, version);

        Class.forName(BOOTSTRAP_CLASS, true, gameClassLoader)
                .getMethod(BOOTSTRAP_METHOD)
                .invoke(null);

        Class<?> blocksClass = Class.forName("net.minecraft.class_2246", true, gameClassLoader);
        int count = 0;
        for (Field field : blocksClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !"net.minecraft.class_2248".equals(field.getType().getName())) {
                continue;
            }
            field.setAccessible(true);
            if (field.get(null) != null) {
                count++;
            }
        }
        return count;
    }

    private MinecraftBlockSmoke() {
    }
}
