package online.yudream.voxelith.resource.application;

import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.ModelVariantData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.resource.domain.biome.BiomeColorTable;
import online.yudream.voxelith.resource.domain.biome.BiomeEffects;
import online.yudream.voxelith.resource.domain.biome.BiomeSource;
import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.resource.domain.model.BlockstateResolver;
import online.yudream.voxelith.resource.domain.model.ElementFace;
import online.yudream.voxelith.resource.domain.model.ElementRotation;
import online.yudream.voxelith.resource.domain.model.ModelElement;
import online.yudream.voxelith.resource.domain.model.ModelResolutionException;
import online.yudream.voxelith.resource.domain.model.ModelResolver;
import online.yudream.voxelith.resource.domain.model.ModelVariant;
import online.yudream.voxelith.resource.domain.model.ResolvedModel;
import online.yudream.voxelith.resource.domain.pack.PackResource;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.resource.domain.parse.BlockstateParser;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认目录实现：基于包栈懒解析 blockstate 与模型，结果进程内缓存。
 */
public final class DefaultResolvedResourceCatalog implements ResolvedResourceCatalog {

    private final PackStack packs;
    private final BlockstateParser blockstateParser;
    private final ModelResolver modelResolver;
    private final BlockstateResolver blockstateResolver = new BlockstateResolver();
    private final BiomeSource biomeSource;

    private final Map<Identifier, Optional<BlockstateDefinition>> blockstateCache = new ConcurrentHashMap<>();
    private final Map<Identifier, Optional<ModelData>> modelCache = new ConcurrentHashMap<>();
    private volatile List<Identifier> blocksCache;
    private volatile BiomeColorTable biomeColorTable;

    public DefaultResolvedResourceCatalog(PackStack packs,
                                          BlockstateParser blockstateParser,
                                          ModelResolver modelResolver,
                                          BiomeSource biomeSource) {
        this.packs = packs;
        this.blockstateParser = blockstateParser;
        this.modelResolver = modelResolver;
        this.biomeSource = biomeSource;
    }

    @Override
    public List<Identifier> blocks() {
        List<Identifier> cached = blocksCache;
        if (cached == null) {
            cached = packs.listBlocksWithBlockstate().stream()
                    .sorted(java.util.Comparator.comparing(Identifier::toString))
                    .toList();
            blocksCache = cached;
        }
        return cached;
    }

    @Override
    public List<VariantGroup> selectVariantGroups(Identifier block, Map<String, String> state) {
        Optional<BlockstateDefinition> definition = blockstateCache.computeIfAbsent(block, this::loadBlockstate);
        if (definition.isEmpty()) {
            return List.of();
        }
        List<List<ModelVariant>> groups = blockstateResolver.select(definition.get(), state);
        List<VariantGroup> result = new ArrayList<>(groups.size());
        for (List<ModelVariant> group : groups) {
            result.add(new VariantGroup(group.stream().map(DefaultResolvedResourceCatalog::toData).toList()));
        }
        return result;
    }

    @Override
    public Optional<ModelData> model(Identifier modelId) {
        return modelCache.computeIfAbsent(modelId, this::loadModel);
    }

    @Override
    public Optional<byte[]> texture(Identifier textureId) {
        return packs.texture(textureId).map(PackResource::bytes);
    }

    @Override
    public int biomeGrassColor(String biomeId) {
        return biomeColorTable().grassColor(effects(biomeId));
    }

    @Override
    public int biomeFoliageColor(String biomeId) {
        return biomeColorTable().foliageColor(effects(biomeId));
    }

    @Override
    public int biomeWaterColor(String biomeId) {
        return biomeColorTable().waterColor(effects(biomeId));
    }

    private BiomeEffects effects(String biomeId) {
        return biomeSource.effects(biomeId).orElse(BiomeEffects.DEFAULT);
    }

    /** 懒加载 colormap（PNG 解码一次性开销，未启用群系染色的链路零成本）。 */
    private BiomeColorTable biomeColorTable() {
        BiomeColorTable table = biomeColorTable;
        if (table == null) {
            synchronized (this) {
                table = biomeColorTable;
                if (table == null) {
                    table = new BiomeColorTable(
                            biomeSource.colormap("grass").orElse(null),
                            biomeSource.colormap("foliage").orElse(null));
                    biomeColorTable = table;
                }
            }
        }
        return table;
    }

    @Override
    public void close() {
        for (ResourcePack pack : packs.packs()) {
            if (pack instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响其他包释放
                }
            }
        }
    }

    private Optional<BlockstateDefinition> loadBlockstate(Identifier block) {
        return packs.blockstate(block).map(res -> blockstateParser.parse(res.asUtf8()));
    }

    private Optional<ModelData> loadModel(Identifier modelId) {
        try {
            return Optional.of(toData(modelResolver.resolve(modelId)));
        } catch (ModelResolutionException e) {
            return Optional.empty();
        }
    }

    private static ModelVariantData toData(ModelVariant variant) {
        return new ModelVariantData(
                variant.model().toString(), variant.x(), variant.y(), variant.uvLock(), variant.weight());
    }

    private static ModelData toData(ResolvedModel model) {
        Map<String, String> textures = new LinkedHashMap<>();
        model.textures().forEach((var, id) -> textures.put(var, id.toString()));

        List<ModelData.ElementData> elements = new ArrayList<>();
        for (ModelElement element : model.elements()) {
            ModelData.RotationData rotation = null;
            if (element.rotation() != null) {
                ElementRotation r = element.rotation();
                rotation = new ModelData.RotationData(r.origin(), r.axis().name(), r.angle(), r.rescale());
            }
            Map<String, ModelData.FaceData> faces = new LinkedHashMap<>();
            element.faces().forEach((direction, face) -> faces.put(
                    direction.name().toLowerCase(), toData(face)));
            elements.add(new ModelData.ElementData(
                    element.from(), element.to(), rotation, element.shade(), faces));
        }
        return new ModelData(textures, elements, model.ambientOcclusion());
    }

    private static ModelData.FaceData toData(ElementFace face) {
        return new ModelData.FaceData(
                face.uv(),
                face.texture(),
                face.cullface() == null ? null : face.cullface().name().toLowerCase(),
                face.rotation(),
                face.tintIndex());
    }
}
