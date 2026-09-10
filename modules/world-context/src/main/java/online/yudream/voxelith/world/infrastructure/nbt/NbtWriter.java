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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;

/**
 * NBT 二进制写出器（供合成测试存档、任务状态持久化等场景）。
 */
public final class NbtWriter {

    /** 写"命名根标签"，可选 gzip 压缩（level.dat 用 GZIP，区块 payload 由调用方决定）。 */
    public byte[] writeNamedRoot(String rootName, CompoundTag root, boolean gzip) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            java.io.OutputStream stream = gzip ? new GZIPOutputStream(buffer) : buffer;
            try (DataOutputStream out = new DataOutputStream(stream)) {
                out.writeByte(Tag.COMPOUND);
                out.writeUTF(rootName);
                writeCompound(out, root);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("NBT 写出失败", e);
        }
    }

    void writePayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag) {
            case ByteTag t -> out.writeByte(t.value());
            case ShortTag t -> out.writeShort(t.value());
            case IntTag t -> out.writeInt(t.value());
            case LongTag t -> out.writeLong(t.value());
            case FloatTag t -> out.writeFloat(t.value());
            case DoubleTag t -> out.writeDouble(t.value());
            case ByteArrayTag t -> {
                out.writeInt(t.value().length);
                out.write(t.value());
            }
            case StringTag t -> out.writeUTF(t.value());
            case ListTag t -> {
                out.writeByte(t.elementType());
                out.writeInt(t.size());
                for (Tag item : t.value()) {
                    writePayload(out, item);
                }
            }
            case CompoundTag t -> writeCompound(out, t);
            case IntArrayTag t -> {
                out.writeInt(t.value().length);
                for (int v : t.value()) {
                    out.writeInt(v);
                }
            }
            case LongArrayTag t -> {
                out.writeInt(t.value().length);
                for (long v : t.value()) {
                    out.writeLong(v);
                }
            }
        }
    }

    private void writeCompound(DataOutputStream out, CompoundTag compound) throws IOException {
        for (String key : compound.keys()) {
            Tag value = compound.get(key).orElseThrow();
            out.writeByte(value.typeId());
            out.writeUTF(key);
            writePayload(out, value);
        }
        out.writeByte(Tag.END);
    }
}
