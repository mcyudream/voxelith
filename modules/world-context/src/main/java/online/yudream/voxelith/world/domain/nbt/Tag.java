package online.yudream.voxelith.world.domain.nbt;

/**
 * NBT 标签基类。类型 id 与 MC 二进制格式一致（0=End … 12=LongArray）。
 */
public sealed interface Tag
        permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag,
        StringTag, ByteArrayTag, IntArrayTag, LongArrayTag, ListTag, CompoundTag {

    byte typeId();

    byte END = 0;
    byte BYTE = 1;
    byte SHORT = 2;
    byte INT = 3;
    byte LONG = 4;
    byte FLOAT = 5;
    byte DOUBLE = 6;
    byte BYTE_ARRAY = 7;
    byte STRING = 8;
    byte LIST = 9;
    byte COMPOUND = 10;
    byte INT_ARRAY = 11;
    byte LONG_ARRAY = 12;
}
