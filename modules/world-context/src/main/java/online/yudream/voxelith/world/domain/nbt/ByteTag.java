package online.yudream.voxelith.world.domain.nbt;

public record ByteTag(byte value) implements Tag {
    @Override
    public byte typeId() {
        return BYTE;
    }
}
