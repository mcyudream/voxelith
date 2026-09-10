package online.yudream.voxelith.world.domain.nbt;

import java.util.AbstractList;
import java.util.List;

/**
 * NBT 列表标签。元素类型统一（空列表元素类型为 END）。
 */
public record ListTag(byte elementType, List<Tag> value) implements Tag {

    @Override
    public byte typeId() {
        return LIST;
    }

    public int size() {
        return value.size();
    }

    public Tag get(int index) {
        return value.get(index);
    }

    /** 以 CompoundTag 视角访问（元素类型须为 COMPOUND）。 */
    public CompoundTag getCompound(int index) {
        return (CompoundTag) value.get(index);
    }

    /** 以 StringTag 视角访问。 */
    public String getString(int index) {
        return ((StringTag) value.get(index)).value();
    }

    /** 便捷视图：long[] 列表元素（用于高度图等场景）。 */
    public List<Long> asLongs() {
        return new AbstractList<>() {
            @Override
            public Long get(int index) {
                return ((LongTag) value.get(index)).value();
            }

            @Override
            public int size() {
                return value.size();
            }
        };
    }
}
