package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.infrastructure.nbt.NbtReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 实体来源的唯一实现：**同一个 region 的实体可能写在两处**，都要读。
 *
 * <ul>
 *   <li>{@code <维度>/entities/r.X.Z.mca}：Paper，以及原版 1.20.2 起（实体从区块 NBT 拆出来）；</li>
 *   <li>{@code <维度>/region/r.X.Z.mca} 的区块 NBT（{@code Entities} / {@code entities}）：更早的原版。</li>
 * </ul>
 *
 * <p>地图画（展示框）与实体几何（盔甲架）都用这一份实现，避免「哪条路径、哪个键名」
 * 这类细节被抄成两遍——地图画那条链路就曾经因为漏了维度而完全读不到。</p>
 */
final class AnvilEntitySource {

    private final Path dimensionRoot;
    private final NbtReader nbt = new NbtReader();

    AnvilEntitySource(Path dimensionRoot) {
        this.dimensionRoot = dimensionRoot;
    }

    /** 该 region 的全部实体原始 NBT（两个来源合并）。 */
    List<CompoundTag> entities(RegionPos region) {
        List<CompoundTag> out = new ArrayList<>();
        readFile(dimensionRoot.resolve("entities").resolve(region.fileName()), out);
        readFile(dimensionRoot.resolve("region").resolve(region.fileName()), out);
        return List.copyOf(out);
    }

    private void readFile(Path file, List<CompoundTag> out) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (AnvilRegionReader reader = new AnvilRegionReader(file)) {
            for (AnvilRegionReader.ChunkEntry entry : reader.listChunks()) {
                Optional<byte[]> payload = reader.readChunkPayload(entry.localX(), entry.localZ());
                if (payload.isEmpty()) {
                    continue;
                }
                CompoundTag root = nbt.readNamedRootAuto(payload.get());
                // Paper/1.20.2+ 的 entities 文件用 Entities，更早的原版区块 NBT 用小写 entities
                String key = root.contains("Entities") ? "Entities"
                        : root.contains("entities") ? "entities" : null;
                if (key == null) {
                    continue;
                }
                ListTag list = root.getList(key);
                for (int i = 0; i < list.size(); i++) {
                    out.add(list.getCompound(i));
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析实体失败: " + file, e);
        }
    }
}
