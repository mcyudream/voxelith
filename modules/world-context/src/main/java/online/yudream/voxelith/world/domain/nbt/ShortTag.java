package online.yudream.voxelith.world.domain.nbt;

public record ShortTag(short value) implements Tag {
    @Override
    public byte typeId() {
        return SHORT;
    }
}
