package online.yudream.voxelith.runtime.worker;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 以纯反射引导 fabric-loader 的 Knot classloader（runtime-worker 不编译期依赖 loader——
 * loader jar 由父进程 provision 后拼入 worker classpath）。
 *
 * <p>流程等价于 {@code Knot.launch(args, EnvType.CLIENT)} 的前半段：
 * 定位游戏 jar（fabric.gameJarPath.client）→ 分类 classpath →
 * 用 classpath 上的 intermediary 映射（mappings/mappings.tiny）remap 游戏 jar
 * （缓存于 gameDir/.fabric/remappedJars）→ 返回目标 ClassLoader。
 * 不启动 MinecraftClient main——采集代码直接在该 ClassLoader 上反射执行。</p>
 */
public final class FabricKnotBootstrap {

    /**
     * @return Knot 目标 ClassLoader（可加载 remap 后的 net.minecraft.class_* 与 mod 类）
     */
    public static ClassLoader start(Path gameJar, Path gameDir, List<Path> modJars) {
        System.setProperty("fabric.gameJarPath.client", gameJar.toAbsolutePath().toString());
        if (!modJars.isEmpty()) {
            System.setProperty("fabric.addMods", modJars.stream()
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        try {
            Class<?> envTypeClass = Class.forName("net.fabricmc.api.EnvType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object client = Enum.valueOf((Class<? extends Enum>) envTypeClass, "CLIENT");
            Class<?> knotClass = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
            Object knot = knotClass.getConstructor(envTypeClass).newInstance(client);
            String[] args = {"--gameDir", gameDir.toAbsolutePath().toString()};
            return (ClassLoader) knotClass.getMethod("init", String[].class).invoke(knot, (Object) args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Knot init 失败: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fabric-loader 不在 classpath 或 API 不兼容: " + e.getMessage(), e);
        }
    }

    private FabricKnotBootstrap() {
    }
}
