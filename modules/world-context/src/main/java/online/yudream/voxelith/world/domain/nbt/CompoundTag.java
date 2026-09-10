package online.yudream.voxelith.world.domain.nbt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NBT 复合标签（键值树）。提供类型化便捷访问。
 */
public final class CompoundTag implements Tag {

    private final Map<String, Tag> entries;

    public CompoundTag(Map<String, Tag> entries) {
        this.entries = new LinkedHashMap<>(entries);
    }

    public static CompoundTag empty() {
        return new CompoundTag(Map.of());
    }

    @Override
    public byte typeId() {
        return COMPOUND;
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    public Set<String> keys() {
        return entries.keySet();
    }

    public Optional<Tag> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public CompoundTag getCompound(String key) {
        return (CompoundTag) entries.get(key);
    }

    public ListTag getList(String key) {
        return (ListTag) entries.get(key);
    }

    public int getInt(String key) {
        return ((IntTag) entries.get(key)).value();
    }

    public long getLong(String key) {
        return ((LongTag) entries.get(key)).value();
    }

    public byte getByte(String key) {
        return ((ByteTag) entries.get(key)).value();
    }

    public String getString(String key) {
        return ((StringTag) entries.get(key)).value();
    }

    public byte[] getByteArray(String key) {
        return ((ByteArrayTag) entries.get(key)).value();
    }

    public long[] getLongArray(String key) {
        return ((LongArrayTag) entries.get(key)).value();
    }

    public int getIntOrDefault(String key, int fallback) {
        Tag tag = entries.get(key);
        return tag instanceof IntTag i ? i.value() : fallback;
    }

    public String getStringOrDefault(String key, String fallback) {
        Tag tag = entries.get(key);
        return tag instanceof StringTag s ? s.value() : fallback;
    }
}
