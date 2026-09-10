package online.yudream.voxelith.resource.domain.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 资源包栈：按游戏语义叠加（优先级高者覆盖低者，等价于游戏内"后选的包在上"）。
 * 查询时从高优先级向低优先级逐个命中。
 */
public final class PackStack {

    private final List<ResourcePack> packs;

    private PackStack(List<ResourcePack> packs) {
        this.packs = packs;
    }

    public static PackStack of(List<ResourcePack> packs) {
        List<ResourcePack> sorted = new ArrayList<>(packs);
        sorted.sort(Comparator.comparingInt(ResourcePackHolder::priorityOf).reversed());
        return new PackStack(List.copyOf(sorted));
    }

    public List<ResourcePack> packs() {
        return packs;
    }

    public Optional<PackResource> blockstate(Identifier block) {
        return first(p -> p.blockstate(block));
    }

    public Optional<PackResource> model(Identifier model) {
        return first(p -> p.model(model));
    }

    public Optional<PackResource> texture(Identifier texture) {
        return first(p -> p.texture(texture));
    }

    /** 任意路径原始资源（相对包根），按包栈优先级命中。 */
    public Optional<PackResource> raw(String path) {
        return first(p -> p.raw(path));
    }

    /** 全部包中出现过 blockstate 的方块并集（高优先级包同名单方块整体覆盖，不做字段级合并）。 */
    public Set<Identifier> listBlocksWithBlockstate() {
        Set<Identifier> all = new LinkedHashSet<>();
        for (ResourcePack pack : packs) {
            all.addAll(pack.listBlocksWithBlockstate());
        }
        return all;
    }

    private Optional<PackResource> first(java.util.function.Function<ResourcePack, Optional<PackResource>> query) {
        for (ResourcePack pack : packs) {
            Optional<PackResource> hit = query.apply(pack);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** 仅用于排序读取优先级，避免给 ResourcePack 接口强加 priority 语义。 */
    private static final class ResourcePackHolder {
        private static int priorityOf(ResourcePack pack) {
            return pack instanceof Prioritized prioritized ? prioritized.priority() : 0;
        }
    }

    /** infrastructure 实现可选实现本接口以携带优先级。 */
    public interface Prioritized {
        int priority();
    }
}
