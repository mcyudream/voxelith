package online.yudream.voxelith.resource.domain.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

/**
 * MC 资产路径约定：assets/{namespace}/(blockstates|models|textures)/{path}.(json|png)。
 */
public final class AssetPaths {

    private AssetPaths() {
    }

    public static String blockstate(Identifier block) {
        return "assets/" + block.namespace() + "/blockstates/" + block.path() + ".json";
    }

    public static String model(Identifier model) {
        return "assets/" + model.namespace() + "/models/" + model.path() + ".json";
    }

    public static String texture(Identifier texture) {
        return "assets/" + texture.namespace() + "/textures/" + texture.path() + ".png";
    }

    /** 从 blockstates 相对路径反推方块标识，如 "blockstates/stone.json" + ns → minecraft:stone。 */
    public static Identifier blockFromBlockstatePath(String namespace, String fileName) {
        String path = fileName;
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        return new Identifier(namespace, path);
    }
}
