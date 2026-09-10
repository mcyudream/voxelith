package online.yudream.voxelith.world.domain.nbt;

public record IntTag(int value) implements Tag {
    @Override
    public byte typeId() {
        return INT;
    }
}
