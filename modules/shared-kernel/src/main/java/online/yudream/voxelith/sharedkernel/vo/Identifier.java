package online.yudream.voxelith.sharedkernel.vo;

/**
 * 资源标识符，对应 MC 的 namespace:path 格式。
 */
public record Identifier(String namespace, String path) {

    public static final String MINECRAFT = "minecraft";

    public Identifier {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
    }

    /** 解析 "namespace:path"，无命名空间时默认 minecraft。 */
    public static Identifier parse(String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new Identifier(MINECRAFT, raw);
        }
        return new Identifier(raw.substring(0, colon), raw.substring(colon + 1));
    }

    public static Identifier minecraft(String path) {
        return new Identifier(MINECRAFT, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
