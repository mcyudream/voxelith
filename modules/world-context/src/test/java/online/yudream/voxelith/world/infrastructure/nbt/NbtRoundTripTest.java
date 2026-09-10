package online.yudream.voxelith.world.infrastructure.nbt;

import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.domain.nbt.IntTag;
import online.yudream.voxelith.world.domain.nbt.ListTag;
import online.yudream.voxelith.world.domain.nbt.LongArrayTag;
import online.yudream.voxelith.world.domain.nbt.StringTag;
import online.yudream.voxelith.world.domain.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NbtRoundTripTest {

    private final NbtWriter writer = new NbtWriter();
    private final NbtReader reader = new NbtReader();

    @Test
    void roundTripsCompoundWithAllFieldKinds() {
        Map<String, Tag> inner = new LinkedHashMap<>();
        inner.put("Name", new StringTag("minecraft:stone"));
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("DataVersion", new IntTag(3465));
        root.put("palette", new ListTag(Tag.COMPOUND, List.of(new CompoundTag(inner))));
        root.put("data", new LongArrayTag(new long[]{0x1234abcdL, -1L}));

        byte[] uncompressed = writer.writeNamedRoot("", new CompoundTag(root), false);
        CompoundTag back = reader.readNamedRoot(uncompressed, NbtReader.Compression.NONE);

        assertThat(back.getInt("DataVersion")).isEqualTo(3465);
        assertThat(back.getList("palette").getCompound(0).getString("Name")).isEqualTo("minecraft:stone");
        assertThat(back.getLongArray("data")).containsExactly(0x1234abcdL, -1L);
    }

    @Test
    void gzipRoundTripAndAutoDetect() {
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("k", new StringTag("v"));
        byte[] gzipped = writer.writeNamedRoot("", new CompoundTag(root), true);

        assertThat(NbtReader.Compression.detect(gzipped)).isEqualTo(NbtReader.Compression.GZIP);
        assertThat(reader.readNamedRootAuto(gzipped).getString("k")).isEqualTo("v");
    }
}
