package online.yudream.voxelith.world.domain.nbt;

public record StringTag(String value) implements Tag {
    @Override
    public byte typeId() {
        return STRING;
    }
}
