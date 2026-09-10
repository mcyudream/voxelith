package online.yudream.voxelith.world.domain.nbt;

public record IntArrayTag(int[] value) implements Tag {
    @Override
    public byte typeId() {
        return INT_ARRAY;
    }
}
