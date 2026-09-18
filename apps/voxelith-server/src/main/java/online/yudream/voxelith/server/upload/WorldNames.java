package online.yudream.voxelith.server.upload;

/**
 * 存档 id / 地图 id 的命名规则。
 *
 * <p>这些 id 会直接变成目录名（{@code upload-dir/<id>}、{@code publish-dir/<mapId>}）与 URL 片段，
 * 所以只做「文件系统与 URL 安全」这一条约束：允许中文等 Unicode（毕业设计里的地图名就是中文），
 * 但挡掉路径分隔符、Windows 保留字符、控制字符与前后导点空格——这些要么能穿越目录，要么会让目录建不出来。</p>
 */
public final class WorldNames {

    /** Windows 保留字符 + 路径分隔符：出现在目录名里会建不出目录或产生歧义。 */
    private static final String ILLEGAL = "<>:\"/\\|?*";

    private static final int MAX_LENGTH = 48;

    private WorldNames() {
    }

    /**
     * 把任意名称折成安全的 id 片段：非法字符换成 '-'，连续 '-' 合并，去掉首尾的点和空格。
     *
     * @param raw      原始名称（可能是中文）
     * @param fallback 折完为空时的兜底（如 "world"）
     */
    public static String slug(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c == 0x7F || ILLEGAL.indexOf(c) >= 0) {
                appendDash(out);
            } else if (c == ' ' || c == '\t') {
                appendDash(out);
            } else {
                out.append(c);
            }
            if (out.length() >= MAX_LENGTH) {
                break;
            }
        }
        String slug = out.toString().replaceAll("-+$", "").replaceAll("^[.\\s-]+", "");
        return slug.isEmpty() ? fallback : slug;
    }

    /** id 是否可用于目录名与 URL（只挡危险字符，不限制字符集）。 */
    public static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.length() > MAX_LENGTH) {
            return false;
        }
        if (id.contains("..")) {
            return false;
        }
        for (char c : id.toCharArray()) {
            if (c < 0x20 || c == 0x7F || ILLEGAL.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 名称的短哈希（同一名称重复登记时用来区分，也避免不同名称折出同一个 slug）。 */
    public static String shortHash(String raw) {
        return Integer.toHexString((raw == null ? "" : raw).hashCode() & 0xFFFFF);
    }

    private static void appendDash(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
            out.append('-');
        }
    }
}
