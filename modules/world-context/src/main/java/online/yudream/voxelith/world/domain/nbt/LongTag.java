package online.yudream.voxelith.world.domain.nbt;

public record LongTag(long value) implements Tag {
    @Override
    public byte typeId() {
        return LONG;
    }
}
