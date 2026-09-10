package online.yudream.voxelith.world.domain.nbt;

public record LongArrayTag(long[] value) implements Tag {
    @Override
    public byte typeId() {
        return LONG_ARRAY;
    }
}
