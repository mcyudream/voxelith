package online.yudream.voxelith.runtime.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ServiceLoader;

/**
 * 按游戏 ClassLoader 上的 API 签名选择采集适配器。
 * 内置 Fabric 适配器始终参与；外部 jar 通过 {@link ServiceLoader} 注册。
 */
public final class HarvestAdapters {

    public static HarvestAdapter resolve(ClassLoader gameClassLoader) {
        HarvestAdapter fabric = new FabricHarvestAdapter();
        if (fabric.supports(gameClassLoader)) {
            return fabric;
        }
        for (HarvestAdapter adapter : ServiceLoader.load(HarvestAdapter.class, HarvestAdapter.class.getClassLoader())) {
            if (adapter.supports(gameClassLoader)) {
                return adapter;
            }
        }
        throw new IllegalStateException(
                "无可用 HarvestAdapter：当前游戏 ClassLoader 不匹配内置 Fabric 签名，"
                        + "也未发现 META-INF/services 扩展。"
                        + " Blocks=" + present(gameClassLoader, "net.minecraft.class_2246")
                        + " ZipPack=" + present(gameClassLoader, "net.minecraft.class_3258"));
    }

    static boolean present(ClassLoader cl, String name) {
        return Reflect.present(cl, name);
    }

    static int countBlockFields(ClassLoader gameClassLoader) throws Exception {
        Class<?> blocksClass = Reflect.init(gameClassLoader, "net.minecraft.class_2246");
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

    /** 优先读 Registries.BLOCK 大小（含模组方块）；失败再退回 Blocks 静态字段。 */
    static int countRegisteredBlocks(ClassLoader gameClassLoader) throws Exception {
        int vanillaFields = countBlockFields(gameClassLoader);
        try {
            Class<?> registries = Reflect.cls(gameClassLoader, "net.minecraft.class_7923");
            Object blockRegistry = registries.getField("field_41175").get(null);
            if (blockRegistry instanceof Iterable<?> iterable) {
                int n = 0;
                for (Object ignored : iterable) {
                    n++;
                }
                return Math.max(n, vanillaFields);
            }
        } catch (ReflectiveOperationException ignored) {
            // 引导未完成或字段改名时退回静态字段
        }
        return vanillaFields;
    }

    private HarvestAdapters() {
    }
}
