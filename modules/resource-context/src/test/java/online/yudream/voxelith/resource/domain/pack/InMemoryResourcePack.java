package online.yudream.voxelith.resource.domain.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 测试用内存资源包。 */
public final class InMemoryResourcePack implements ResourcePack, PackStack.Prioritized {

    private final String id;
    private final int priority;
    private final Map<String, String> jsonEntries = new HashMap<>();
    private final Set<Identifier> blocks = new java.util.HashSet<>();

    public InMemoryResourcePack(String id, int priority) {
        this.id = id;
        this.priority = priority;
    }

    public InMemoryResourcePack putModel(Identifier model, String json) {
        jsonEntries.put(AssetPaths.model(model), json);
        return this;
    }

    public InMemoryResourcePack putBlockstate(Identifier block, String json) {
        jsonEntries.put(AssetPaths.blockstate(block), json);
        blocks.add(block);
        return this;
    }

    @Override
    public String packId() {
        return id;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Optional<PackResource> blockstate(Identifier block) {
        return Optional.ofNullable(jsonEntries.get(AssetPaths.blockstate(block))).map(s -> new PackResource(s.getBytes()));
    }

    @Override
    public Optional<PackResource> model(Identifier model) {
        return Optional.ofNullable(jsonEntries.get(AssetPaths.model(model))).map(s -> new PackResource(s.getBytes()));
    }

    @Override
    public Optional<PackResource> texture(Identifier texture) {
        return Optional.empty();
    }

    @Override
    public Set<Identifier> listBlocksWithBlockstate() {
        return blocks;
    }
}
