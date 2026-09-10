package online.yudream.voxelith.world.domain.nbt;

public record FloatTag(float value) implements Tag {
    @Override
    public byte typeId() {
        return FLOAT;
    }
}
