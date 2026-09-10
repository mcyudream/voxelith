package online.yudream.voxelith.world.infrastructure.nbt;

import online.yudream.voxelith.world.domain.nbt.ByteArrayTag;
import online.yudream.voxelith.world.domain.nbt.ByteTag;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.DoubleTag;
import online.yudream.voxelith.world.domain.nbt.FloatTag;
import online.yudream.voxelith.world.domain.nbt.IntArrayTag;
import online.yudream.voxelith.world.domain.nbt.IntTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.LongArrayTag;
import online.yudream.voxelith.world.domain.nbt.LongTag;
import online.yudream.voxelith.world.domain.nbt.ShortTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * NBT 二进制读取器（big-endian，与 MC 一致）。支持无压缩/gzip/zlib 三种输入。
 */
public final class NbtReader {

    /** 读"命名根标签"（level.dat、区块 NBT 的标准形态），返回根 CompoundTag。 */
    public CompoundTag readNamedRoot(byte[] bytes, Compression compression) {
        try (DataInputStream in = new DataInputStream(open(bytes, compression))) {
            byte type = in.readByte();
            if (type != Tag.COMPOUND) {
                throw new IllegalArgumentException("NBT 根标签不是 Compound: type=" + type);
            }
            in.readUTF(); // 根名（通常为空串）
            return readCompound(in);
        } catch (IOException e) {
            throw new UncheckedIOException("NBT 读取失败", e);
        }
    }

    /** 自动探测压缩格式（gzip 魔数 1f8b / zlib 魔数 0x78 / 无压缩）。 */
    public CompoundTag readNamedRootAuto(byte[] bytes) {
        return readNamedRoot(bytes, Compression.detect(bytes));
    }

    private static InputStream open(byte[] bytes, Compression compression) throws IOException {
        InputStream raw = new java.io.ByteArrayInputStream(bytes);
        return switch (compression) {
            case GZIP -> new GZIPInputStream(raw);
            case ZLIB -> new InflaterInputStream(raw);
            case NONE -> raw;
        };
    }

    Tag readPayload(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case Tag.BYTE -> new ByteTag(in.readByte());
            case Tag.SHORT -> new ShortTag(in.readShort());
            case Tag.INT -> new IntTag(in.readInt());
            case Tag.LONG -> new LongTag(in.readLong());
            case Tag.FLOAT -> new FloatTag(in.readFloat());
            case Tag.DOUBLE -> new DoubleTag(in.readDouble());
            case Tag.BYTE_ARRAY -> {
                byte[] array = new byte[in.readInt()];
                in.readFully(array);
                yield new ByteArrayTag(array);
            }
            case Tag.STRING -> new StringTag(in.readUTF());
            case Tag.LIST -> {
                byte elementType = in.readByte();
                int size = in.readInt();
                List<Tag> items = new ArrayList<>(Math.max(0, size));
                for (int i = 0; i < size; i++) {
                    items.add(readPayload(in, elementType));
                }
                yield new ListTag(elementType, items);
            }
            case Tag.COMPOUND -> readCompound(in);
            case Tag.INT_ARRAY -> {
                int[] array = new int[in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readInt();
                }
                yield new IntArrayTag(array);
            }
            case Tag.LONG_ARRAY -> {
                long[] array = new long[in.readInt()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = in.readLong();
                }
                yield new LongArrayTag(array);
            }
            default -> throw new IllegalArgumentException("未知 NBT 标签类型: " + type);
        };
    }

    private CompoundTag readCompound(DataInputStream in) throws IOException {
        Map<String, Tag> entries = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.END) {
                break;
            }
            String name = in.readUTF();
            entries.put(name, readPayload(in, type));
        }
        return new CompoundTag(entries);
    }

    public enum Compression {
        NONE, GZIP, ZLIB;

        public static Compression detect(byte[] bytes) {
            if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
                return GZIP;
            }
            if (bytes.length >= 1 && (bytes[0] & 0xff) == 0x78) {
                return ZLIB;
            }
            return NONE;
        }
    }
}
