package online.yudream.voxelith.world.domain.nbt;

public record ByteArrayTag(byte[] value) implements Tag {
    @Override
    public byte typeId() {
        return BYTE_ARRAY;
    }
}
