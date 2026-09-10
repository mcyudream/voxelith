package online.yudream.voxelith.resource.application;

import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;
import online.yudream.voxelith.resource.domain.model.ModelResolutionException;
import online.yudream.voxelith.resource.domain.model.ModelResolver;
import online.yudream.voxelith.resource.domain.model.ModelVariant;
import online.yudream.voxelith.resource.domain.model.MultipartCase;
import online.yudream.voxelith.resource.domain.model.ResolvedModel;
import online.yudream.voxelith.resource.domain.pack.PackStack;
import online.yudream.voxelith.resource.domain.pack.ResourcePack;
import online.yudream.voxelith.resource.domain.pack.ResourcePackFactory;
import online.yudream.voxelith.resource.domain.parse.BlockstateParser;
import online.yudream.voxelith.resource.domain.parse.ModelParser;
import online.yudream.voxelith.resource.domain.registry.BlockModelRegistry;
import online.yudream.voxelith.resource.domain.registry.ResolveReport;
import online.yudream.voxelith.resource.domain.registry.ResolvedBlock;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * resolve 链路用例：包栈叠加 → 枚举 blockstate → 解析模型继承链 → 收集贴图 → 产物落盘。
 * 纯编排逻辑，不包含 IO/序列化细节（经端口委托 infrastructure）。
 */
public class ResolveResourcesUseCase {

    private final ResourcePackFactory packFactory;
    private final BlockstateParser blockstateParser;
    private final ModelParser modelParser;
    private final ResolveArtifactSink sink;

    public ResolveResourcesUseCase(ResourcePackFactory packFactory,
                                   BlockstateParser blockstateParser,
                                   ModelParser modelParser,
                                   ResolveArtifactSink sink) {
        this.packFactory = packFactory;
        this.blockstateParser = blockstateParser;
        this.modelParser = modelParser;
        this.sink = sink;
    }

    public ResolveOutcome resolve(ResolveCommand command) {
        List<ResourcePack> packs = new ArrayList<>();
        for (int i = 0; i < command.packs().size(); i++) {
            packs.add(packFactory.open(command.packs().get(i), i));
        }
        PackStack stack = PackStack.of(packs);

        ResolveReport.Builder report = ResolveReport.builder();
        Map<Identifier, ResolvedBlock> blocks = new LinkedHashMap<>();
        Map<Identifier, ResolvedModel> models = new LinkedHashMap<>();

        Set<Identifier> blockIds = stack.listBlocksWithBlockstate();
        report.blocksFound(blockIds.size());

        ModelResolver modelResolver = new ModelResolver(stack, modelParser);
        int missingModels = 0;

        for (Identifier blockId : blockIds) {
            try {
                Optional<ResolvedBlock> resolved = resolveBlock(blockId, stack, modelResolver, models);
                if (resolved.isPresent()) {
                    blocks.put(blockId, resolved.get());
                    report.blockResolved();
                } else {
                    report.blockUnresolved(blockId.toString(), "blockstate 缺失");
                }
            } catch (ModelResolutionException e) {
                report.blockUnresolved(blockId.toString(), e.getMessage());
                if ("resource.model.missing".equals(e.code())) {
                    missingModels++;
                    report.modelMissing(e.getMessage());
                }
            } catch (RuntimeException e) {
                report.blockUnresolved(blockId.toString(), "解析失败: " + e.getMessage());
            }
        }

        report.modelsFound(models.size() + missingModels);
        models.keySet().forEach(id -> report.modelResolved());

        BlockModelRegistry registry = new BlockModelRegistry(blocks, models);

        Set<Identifier> usedTextures = collectTextures(models, stack, report);
        int exported = sink.writeTextures(command.outputDir(), usedTextures,
                id -> stack.texture(id).map(r -> r.bytes()));

        sink.writeRegistry(command.outputDir(), registry);
        ResolveReport finalReport = report.build();
        sink.writeReport(command.outputDir(), finalReport);

        return new ResolveOutcome(
                finalReport.blocksResolved(), finalReport.blocksFound(),
                finalReport.modelsResolved(), finalReport.modelsFound(),
                exported, finalReport.blockCoverage(), finalReport.modelCoverage());
    }

    private Optional<ResolvedBlock> resolveBlock(Identifier blockId,
                                                 PackStack stack,
                                                 ModelResolver modelResolver,
                                                 Map<Identifier, ResolvedModel> models) {
        return stack.blockstate(blockId).map(resource -> {
            BlockstateDefinition definition = blockstateParser.parse(resource.asUtf8());
            Set<Identifier> refs = referencedModels(definition);
            for (Identifier modelId : refs) {
                if (!models.containsKey(modelId)) {
                    models.put(modelId, modelResolver.resolve(modelId));
                }
            }
            return new ResolvedBlock(blockId, definition, refs);
        });
    }

    private static Set<Identifier> referencedModels(BlockstateDefinition definition) {
        Set<Identifier> refs = new LinkedHashSet<>();
        if (definition instanceof BlockstateDefinition.Variants variants) {
            variants.variants().values().forEach(list -> list.forEach(v -> refs.add(v.model())));
        } else if (definition instanceof BlockstateDefinition.Multipart multipart) {
            for (MultipartCase c : multipart.cases()) {
                c.apply().forEach(v -> refs.add(v.model()));
            }
        }
        return refs;
    }

    private Set<Identifier> collectTextures(Map<Identifier, ResolvedModel> models,
                                            PackStack stack,
                                            ResolveReport.Builder report) {
        Set<Identifier> textures = new LinkedHashSet<>();
        for (ResolvedModel model : models.values()) {
            for (Identifier texture : model.textures().values()) {
                if (stack.texture(texture).isPresent()) {
                    textures.add(texture);
                } else {
                    report.textureMissing(texture.toString());
                }
            }
        }
        return textures;
    }

}
