package online.yudream.voxelith.world.domain.nbt;

public record DoubleTag(double value) implements Tag {
    @Override
    public byte typeId() {
        return DOUBLE;
    }
}
