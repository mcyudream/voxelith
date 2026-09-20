package online.yudream.voxelith.server.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 采集（models.json.gz）该用哪个 MC 版本：**不写死版本**，按证据优先级自动判定。
 *
 * <p>为什么这件事很关键：models.json.gz 里的模型是「某个版本的渲染器烘焙出来的」，
 * 而贴图来自 `packs` 里那个 client jar。两者版本不一致时，模型引用的贴图名在新包里
 * 可能已改名（1.20.3 {@code grass→short_grass}、1.17 {@code grass_path→dirt_path}…），
 * 表现为**大片品红**；反过来模型缺了则表现为**空洞**。
 * 以前默认写死 1.20.1，任何 1.20.5/1.21 的存档都会踩这个坑。</p>
 *
 * <p>优先级（越靠前越可信）：</p>
 * <ol>
 *   <li><b>显式指定</b>（{@code -PmcVersion} / 配置项）——用户说了算；</li>
 *   <li><b>资源包文件名里的版本</b>（{@code client-1.21.1.jar}）——采集必须与包同版本，
 *       否则烘出来的模型与同一个包里的贴图对不上；</li>
 *   <li><b>存档版本</b>（{@code level.dat} 的 {@code Version.Name}）——包名里没版本时的最佳猜测；</li>
 *   <li>兜底常量（1.20.1）。</li>
 * </ol>
 */
public final class McVersionResolver {

    /** 从文件名里抽 MC 版本号：{@code client-1.21.1.jar} → {@code 1.21.1}。 */
    private static final Pattern VERSION = Pattern.compile("(1\\.\\d+(?:\\.\\d+)?)");

    private McVersionResolver() {
    }

    /**
     * @param explicit     显式指定的版本（null/空白 = 未指定）
     * @param packFileName 资源包 jar 的文件名（null/未提供 = 未知）
     * @param worldVersion 存档版本名（null/"unknown" = 未知）
     * @param fallback     全都判不出来时的兜底
     */
    public static String resolve(String explicit, String packFileName, String worldVersion,
                                 String fallback) {
        if (isKnown(explicit)) {
            return explicit.trim();
        }
        String fromPack = versionOf(packFileName);
        if (fromPack != null) {
            return fromPack;
        }
        if (isKnown(worldVersion) && !"unknown".equalsIgnoreCase(worldVersion.trim())) {
            return worldVersion.trim();
        }
        return fallback;
    }

    /** 文件名里的 MC 版本号；抽不到返回 null。 */
    public static String versionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION.matcher(fileName);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 判定结果的来源（写进日志，方便用户确认「为什么用这个版本」）。 */
    public static String source(String explicit, String packFileName, String worldVersion) {
        if (isKnown(explicit)) {
            return "显式指定";
        }
        if (versionOf(packFileName) != null) {
            return "资源包文件名";
        }
        if (isKnown(worldVersion) && !"unknown".equalsIgnoreCase(worldVersion.trim())) {
            return "存档版本";
        }
        return "兜底默认";
    }

    private static boolean isKnown(String value) {
        return value != null && !value.isBlank();
    }
}
