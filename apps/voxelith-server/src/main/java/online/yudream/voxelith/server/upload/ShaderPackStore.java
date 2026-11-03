package online.yudream.voxelith.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 光影包仓库：扫描受管目录下的光影包 zip，并从中读出「配置层」数据。
 *
 * <p>与材质包不同，光影包在查看器里是**纯前端**生效的（内置光影管线的参数预设），
 * 服务端只负责一件事：把用户放进目录的大体积 zip 里的少量配置文本读出来
 * （shaders.properties + 各 GLSL 文件里的标量 #define 默认值），省得浏览器
 * 下载并解压几十 MB 的包只为读几 KB 配置。GLSL 本体不解析、不执行。</p>
 *
 * <p>#define 扫描约定：跳过 {@code //#define}（那是作者注释掉的备选档位），
 * 只收数值/布尔标量，同名以**首次出现**为准（默认值块通常在文件靠前位置）。</p>
 */
public class ShaderPackStore {

    /** 标量 #define：#define NAME value（value 为数字或 true/false 之类的裸标识符）。 */
    private static final Pattern DEFINE_SCALAR = Pattern.compile(
            "^[ \\t]*#define[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]+([0-9][0-9.eE+-]*|[A-Za-z_][A-Za-z0-9_]*)[ \\t]*(?://.*)?$");
    /**
     * OptiFine 常量的另一种声明方式：const float shadowDistance = 192.0;
     * （iterationT / Derivative 等主流包的 shadowDistance、sunPathRotation 都这么写）。
     */
    private static final Pattern CONST_SCALAR = Pattern.compile(
            "^[ \\t]*const[ \\t]+(?:float|int)[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*([-+]?[0-9][0-9.eE+-]*)[ \\t]*;.*$");
    /**
     * MC 光影选项声明（带可选值注释）：#define NAME 默认值 // [v1 v2 ...]，
     * const 声明同款。这正是游戏内光影设置界面读的元数据。
     */
    private static final Pattern DEFINE_OPTION = Pattern.compile(
            "^[ \\t]*#define[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]+([0-9][0-9.eE+-]*|-|true|false)[ \\t]*//[ \\t]*\\[([^\\]]+)\\][ \\t]*$");
    private static final Pattern CONST_OPTION = Pattern.compile(
            "^[ \\t]*const[ \\t]+(?:float|int)[ \\t]+([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*([-+]?[0-9][0-9.eE+-]*)[ \\t]*;[ \\t]*//[ \\t]*\\[([^\\]]+)\\][ \\t]*$");
    /** 单文件读取上限：着色器源文件都是几十 KB 量级，超限说明不是源码。 */
    private static final int MAX_SOURCE_FILE_BYTES = 2 * 1024 * 1024;
    /** 选项与可选值的防御性上限（包里最多几百项，超限说明解析失控）。 */
    private static final int MAX_OPTIONS = 400;
    private static final int MAX_OPTION_VALUES = 64;

    private final Path dir;

    public ShaderPackStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建光影包目录: " + this.dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** 目录里的一个光影包 zip。 */
    public record PackInfo(String name, String path, long sizeBytes) {
    }

    /**
     * 光影包的配置层数据：shaders.properties 原文 + 包内 GLSL 的标量 #define
     * + 带「可选值列表」注释的选项声明（游戏内光影设置界面的数据源）。
     * 映射成查看器参数在**前端**做（与本地文件上传共用同一套逻辑）。
     */
    public record PackConfig(String name, String properties, Map<String, String> defines,
                             List<PackOption> options) {
    }

    /**
     * 一个光影选项：游戏内显示为可切换的取值列表（MC 光影设置的样子）。
     *
     * @param value  当前默认值
     * @param values 包作者声明的全部可选值（按声明顺序）
     */
    public record PackOption(String name, String value, List<String> values) {
    }

    /** 受管目录里的全部光影包（按文件名排序）。 */
    public List<PackInfo> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.list(dir)) {
            List<PackInfo> result = new ArrayList<>();
            for (Path p : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                try {
                    result.add(new PackInfo(p.getFileName().toString(), p.toString(), Files.size(p)));
                } catch (IOException e) {
                    // 读不到大小的条目跳过即可，别让一个坏文件拖垮整个列表
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("扫描光影包目录失败: " + dir, e);
        }
    }

    /**
     * 读一个光影包的配置层。
     *
     * @throws IllegalArgumentException 名字非法 / zip 不存在 / 不是光影包
     */
    public PackConfig readConfig(String rawName) {
        String name = requireSafeName(rawName);
        Path path = dir.resolve(name);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("光影包不存在: " + name);
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            String propertiesEntry = shortestEntry(zip, "shaders.properties");
            if (propertiesEntry == null) {
                throw new IllegalArgumentException(name + " 里没有 shaders.properties（不是光影包？）");
            }
            String properties = readText(zip, propertiesEntry, 512 * 1024);
            Map<String, String> defines = new LinkedHashMap<>();
            List<PackOption> options = new ArrayList<>();
            scanSources(zip, folderOf(propertiesEntry), defines, options);
            return new PackConfig(stripZipSuffix(name), properties, defines, List.copyOf(options));
        } catch (IOException e) {
            throw new IllegalArgumentException("无法打开光影包 zip: " + name, e);
        }
    }

    /** 文件名只允许安全字符段（防路径穿越），保留中文与空格。 */
    private static String requireSafeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.contains(":") || name.contains("\"") || name.contains("<") || name.contains(">")) {
            throw new IllegalArgumentException("非法的光影包名: " + raw);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("光影包须是 .zip 文件: " + name);
        }
        return name;
    }

    /** 路径最短的匹配条目（光影包可能带子目录，与 ZipResourcePack 的根探测同思路）。 */
    private static String shortestEntry(ZipFile zip, String suffix) {
        String best = null;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(suffix) || name.endsWith("/")) {
                continue;
            }
            if (best == null || name.length() < best.length()) {
                best = name;
            }
        }
        return best;
    }

    /**
     * 扫描 shaders.properties 同目录（含子目录）着色器源文件：
     * 标量 #define/const 进 defines（默认值兜底用），带可选值注释的进 options
     * （前端光影配置面板用）。首次出现为准；每个文件限 2MB，防御性跳过异常条目。
     */
    private static void scanSources(ZipFile zip, String folder,
                                    Map<String, String> defines, List<PackOption> options) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(folder) || name.length() <= folder.length()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".vsh") && !lower.endsWith(".fsh")
                    && !lower.endsWith(".gsh") && !lower.endsWith(".glsl")) {
                continue;
            }
            if (entry.getSize() > MAX_SOURCE_FILE_BYTES) {
                continue;
            }
            try {
                String text = readText(zip, name, MAX_SOURCE_FILE_BYTES);
                for (String line : text.split("\r?\n")) {
                    Matcher option = DEFINE_OPTION.matcher(line);
                    if (!option.matches()) {
                        option = CONST_OPTION.matcher(line);
                    }
                    if (option.matches() && options.size() < MAX_OPTIONS) {
                        List<String> values = optionValues(option.group(3));
                        if (!values.isEmpty()) {
                            String optionName = option.group(1);
                            boolean known = options.stream().anyMatch(o -> o.name().equals(optionName));
                            if (!known) {
                                options.add(new PackOption(optionName, option.group(2), values));
                            }
                        }
                    }
                    // 选项行的默认值同时进 defines（映射兜底）；无注释的标量 define/const 也收
                    Matcher scalar = DEFINE_SCALAR.matcher(line);
                    if (!scalar.matches()) {
                        scalar = CONST_SCALAR.matcher(line);
                    }
                    if (scalar.matches()) {
                        defines.putIfAbsent(scalar.group(1), scalar.group(2));
                    }
                }
            } catch (IOException | UncheckedIOException | IllegalStateException e) {
                // 单个源文件读不出来不影响整体配置
            }
        }
    }

    /** 可选值列表按空白切分，截断到防御上限；空列表视为无效声明。 */
    private static List<String> optionValues(String bracket) {
        List<String> values = new ArrayList<>();
        for (String piece : bracket.trim().split("\\s+")) {
            if (!piece.isBlank()) {
                values.add(piece);
            }
            if (values.size() >= MAX_OPTION_VALUES) {
                break;
            }
        }
        return values;
    }

    private static String readText(ZipFile zip, String entryName, int maxBytes) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IOException("条目不存在: " + entryName);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("条目过大: " + entryName);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String folderOf(String entryName) {
        int i = entryName.lastIndexOf('/');
        return i < 0 ? "" : entryName.substring(0, i + 1);
    }

    private static String stripZipSuffix(String name) {
        return name.replaceAll("(?i)\\.zip$", "");
    }
}
