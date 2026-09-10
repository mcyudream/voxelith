package online.yudream.voxelith.resource.infrastructure.biome;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import online.yudream.voxelith.resource.domain.biome.BiomeEffects;
import online.yudream.voxelith.resource.domain.biome.BiomeSource;
import online.yudream.voxelith.resource.domain.pack.PackResource;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于包栈的群系数据源：群系定义读 data/{ns}/worldgen/biome/{path}.json，
 * colormap 读 assets/minecraft/textures/colormap/{grass,foliage}.png；结果进程内缓存。
 */
public final class PackBiomeSource implements BiomeSource {

    private final PackStack packs;
    private final Gson gson = new Gson();
    private final Map<String, Optional<BiomeEffects>> effectsCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<int[]>> colormapCache = new ConcurrentHashMap<>();

    public PackBiomeSource(PackStack packs) {
        this.packs = packs;
    }

    @Override
    public Optional<BiomeEffects> effects(String biomeId) {
        return effectsCache.computeIfAbsent(biomeId, this::loadEffects);
    }

    @Override
    public Optional<int[]> colormap(String name) {
        return colormapCache.computeIfAbsent(name, this::loadColormap);
    }

    private Optional<BiomeEffects> loadEffects(String biomeId) {
        Identifier id = Identifier.parse(biomeId);
        String path = "data/" + id.namespace() + "/worldgen/biome/" + id.path() + ".json";
        return packs.raw(path).map(PackResource::asUtf8).map(this::parseEffects);
    }

    private BiomeEffects parseEffects(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        double temperature = root.has("temperature") ? root.get("temperature").getAsDouble() : 0.8;
        double downfall = root.has("downfall") ? root.get("downfall").getAsDouble() : 0.4;
        Integer grassColor = null;
        Integer foliageColor = null;
        String modifier = "none";
        Integer waterColor = null;
        if (root.has("effects")) {
            JsonObject effects = root.getAsJsonObject("effects");
            if (effects.has("grass_color")) {
                grassColor = effects.get("grass_color").getAsInt();
            }
            if (effects.has("foliage_color")) {
                foliageColor = effects.get("foliage_color").getAsInt();
            }
            if (effects.has("grass_color_modifier")) {
                modifier = effects.get("grass_color_modifier").getAsString();
            }
            if (effects.has("water_color")) {
                waterColor = effects.get("water_color").getAsInt();
            }
        }
        return new BiomeEffects(temperature, downfall, grassColor, foliageColor, modifier, waterColor);
    }

    private Optional<int[]> loadColormap(String name) {
        return packs.texture(new Identifier("minecraft", "colormap/" + name))
                .map(res -> decode(res.bytes(), name));
    }

    private static int[] decode(byte[] bytes, String name) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("colormap 解码失败: " + name, e);
        }
        if (image == null) {
            throw new IllegalArgumentException("无法识别的 colormap 格式: " + name);
        }
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
