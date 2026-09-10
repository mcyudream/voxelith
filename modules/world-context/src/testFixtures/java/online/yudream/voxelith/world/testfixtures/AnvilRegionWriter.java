package online.yudream.voxelith.world.testfixtures;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.world.domain.nbt.CompoundTag;
import online.yudream.voxelith.world.infrastructure.nbt.NbtWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * Anvil region 文件写出器（顺序分配扇区，仅供合成测试存档用）。
 */
public final class AnvilRegionWriter {

    private static final int COMPRESSION_ZLIB = 2;

    private final NbtWriter nbtWriter = new NbtWriter();
    private final Map<ChunkPos, CompoundTag> chunks = new LinkedHashMap<>();

    public AnvilRegionWriter putChunk(ChunkPos posInRegionLocal, CompoundTag chunkNbt) {
        chunks.put(posInRegionLocal, chunkNbt);
        return this;
    }

    public void write(Path regionDir, RegionPos region) {
        try {
            Files.createDirectories(regionDir);
            Path file = regionDir.resolve(region.fileName());
            // 先在内存中排布扇区，再一次性落盘
            Map<ChunkPos, Integer> headerEntries = new LinkedHashMap<>();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int nextSector = 2; // 0/1 号扇区是头表
            for (Map.Entry<ChunkPos, CompoundTag> entry : chunks.entrySet()) {
                byte[] nbt = nbtWriter.writeNamedRoot("", entry.getValue(), false);
                byte[] compressed = zlib(nbt);
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                payload.write((compressed.length + 1) >>> 24);
                payload.write((compressed.length + 1) >>> 16);
                payload.write((compressed.length + 1) >>> 8);
                payload.write(compressed.length + 1);
                payload.write(COMPRESSION_ZLIB);
                payload.write(compressed);
                byte[] bytes = payload.toByteArray();
                int sectors = (bytes.length + 4095) / 4096;
                headerEntries.put(entry.getKey(), (nextSector << 8) | sectors);
                body.write(bytes);
                body.write(new byte[sectors * 4096 - bytes.length]);
                nextSector += sectors;
            }

            try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
                out.setLength(0);
                byte[] offsets = new byte[4096];
                byte[] timestamps = new byte[4096];
                int now = (int) (System.currentTimeMillis() / 1000);
                for (Map.Entry<ChunkPos, Integer> entry : headerEntries.entrySet()) {
                    int index = entry.getKey().localX() + entry.getKey().localZ() * 32;
                    int value = entry.getValue();
                    offsets[index * 4] = (byte) (value >>> 24);
                    offsets[index * 4 + 1] = (byte) (value >>> 16);
                    offsets[index * 4 + 2] = (byte) (value >>> 8);
                    offsets[index * 4 + 3] = (byte) value;
                    timestamps[index * 4] = (byte) (now >>> 24);
                    timestamps[index * 4 + 1] = (byte) (now >>> 16);
                    timestamps[index * 4 + 2] = (byte) (now >>> 8);
                    timestamps[index * 4 + 3] = (byte) now;
                }
                out.write(offsets);
                out.write(timestamps);
                out.write(body.toByteArray());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写 region 文件失败", e);
        }
    }

    private static byte[] zlib(byte[] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(buffer)) {
            deflater.write(data);
        }
        return buffer.toByteArray();
    }
}
