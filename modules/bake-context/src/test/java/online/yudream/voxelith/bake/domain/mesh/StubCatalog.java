package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.ModelVariantData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存目录：blockId → cube_all 模型（贴图取 "minecraft:block/" + 路径名）。
 */
public final class StubCatalog implements ResolvedResourceCatalog {

    private final Map<String, ModelData> models = new LinkedHashMap<>();

    /** 注册一个 cube_all 方块，贴图 id 自动推导。 */
    public StubCatalog cubeAllBlock(String blockId) {
        models.put(blockId, cubeAll("minecraft:block/" + Identifier.parse(blockId).path()));
        return this;
    }

    /** 注册自定义模型（如 grass_block 三面贴图）。 */
    public StubCatalog block(String blockId, ModelData model) {
        models.put(blockId, model);
        return this;
    }

    @Override
    public List<Identifier> blocks() {
        return models.keySet().stream().map(Identifier::parse).toList();
    }

    @Override
    public List<VariantGroup> selectVariantGroups(Identifier block, Map<String, String> state) {
        if (!models.containsKey(block.toString())) {
            return List.of();
        }
        return List.of(new VariantGroup(List.of(
                new ModelVariantData(block.toString(), 0, 0, false, 1))));
    }

    @Override
    public Optional<ModelData> model(Identifier modelId) {
        return Optional.ofNullable(models.get(modelId.toString()));
    }

    @Override
    public Optional<byte[]> texture(Identifier textureId) {
        return Optional.empty();
    }

    @Override
    public int biomeGrassColor(String biomeId) {
        return 0x91BD59;
    }

    @Override
    public int biomeFoliageColor(String biomeId) {
        return 0x77AB2F;
    }

    @Override
    public int biomeWaterColor(String biomeId) {
        return 0x3F76E4;
    }

    public static ModelData cubeAll(String texture) {
        Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
        for (String dir : List.of("down", "up", "north", "south", "west", "east")) {
            faces.put(dir, new ModelData.FaceData(null, "#all", dir, 0, -1));
        }
        return new ModelData(
                Map.of("all", texture),
                List.of(new ModelData.ElementData(
                        new float[]{0, 0, 0}, new float[]{16, 16, 16}, null, true, faces)),
                true);
    }

    /** 原版 grass_block 语义：顶/底/侧面贴图分离，顶面带 tint。 */
    public static ModelData grassBlock() {
        Map<String, String> textures = Map.of(
                "down", "minecraft:block/dirt",
                "up", "minecraft:block/grass_block_top",
                "side", "minecraft:block/grass_block_side");
        Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
        faces.put("down", new ModelData.FaceData(null, "#down", "down", 0, -1));
        faces.put("up", new ModelData.FaceData(null, "#up", "up", 0, 0));
        faces.put("north", new ModelData.FaceData(null, "#side", "north", 0, -1));
        faces.put("south", new ModelData.FaceData(null, "#side", "south", 0, -1));
        faces.put("west", new ModelData.FaceData(null, "#side", "west", 0, -1));
        faces.put("east", new ModelData.FaceData(null, "#side", "east", 0, -1));
        return new ModelData(
                textures,
                List.of(new ModelData.ElementData(
                        new float[]{0, 0, 0}, new float[]{16, 16, 16}, null, true, faces)),
                true);
    }

    public static StubCatalog superflat() {
        return new StubCatalog()
                .cubeAllBlock("minecraft:bedrock")
                .cubeAllBlock("minecraft:stone")
                .cubeAllBlock("minecraft:dirt")
                .block("minecraft:grass_block", grassBlock());
    }
}
