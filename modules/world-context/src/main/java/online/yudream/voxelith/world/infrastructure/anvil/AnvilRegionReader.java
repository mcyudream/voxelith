package online.yudream.voxelith.world.infrastructure.anvil;

import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.world.infrastructure.nbt.NbtReader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Anvil region 文件读取器：4KiB 扇区、双 1024 项头表（偏移/时间戳）、
 * 块载荷格式 [4字节长度][1字节压缩类型][数据]。
 */
public final class AnvilRegionReader implements AutoCloseable {

    /** 区块载荷压缩类型。 */
    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_ZLIB = 2;
    public static final int COMPRESSION_NONE = 3;

    private final RandomAccessFile file;

    public AnvilRegionReader(Path regionFile) {
        try {
            this.file = new RandomAccessFile(regionFile.toFile(), "r");
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开 region 文件: " + regionFile, e);
        }
    }

    /** 扫描头表，列出存在的区块（region 内局部坐标 → 时间戳）。 */
    public List<ChunkEntry> listChunks() {
        List<ChunkEntry> entries = new ArrayList<>();
        try {
            if (file.length() < 8192) {
                return entries;
            }
            for (int i = 0; i < 1024; i++) {
                file.seek(i * 4L);
                int offsetEntry = file.readInt();
                int offset = offsetEntry >>> 8;
                int sectors = offsetEntry & 0xff;
                if (offset == 0 || sectors == 0) {
                    continue;
                }
                file.seek(4096L + i * 4L);
                int timestamp = file.readInt();
                entries.add(new ChunkEntry(i % 32, i / 32, timestamp));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 region 头表失败", e);
        }
        return entries;
    }

    /** 读区块载荷原始字节（已解压）。 */
    public Optional<byte[]> readChunkPayload(int localX, int localZ) {
        try {
            int index = localX + localZ * 32;
            file.seek(index * 4L);
            int offsetEntry = file.readInt();
            int offset = offsetEntry >>> 8;
            int sectors = offsetEntry & 0xff;
            if (offset == 0 || sectors == 0) {
                return Optional.empty();
            }
            file.seek(offset * 4096L);
            int length = file.readInt();
            int compression = file.readByte();
            byte[] payload = new byte[length - 1];
            file.readFully(payload);
            byte[] decompressed = switch (compression) {
                case COMPRESSION_GZIP -> decompress(payload, NbtReader.Compression.GZIP);
                case COMPRESSION_ZLIB -> decompress(payload, NbtReader.Compression.ZLIB);
                case COMPRESSION_NONE -> payload;
                default -> throw new IllegalStateException(
                        "不支持的区块压缩类型 " + compression + "（lz4 需额外依赖，暂未接入）");
            };
            return Optional.of(decompressed);
        } catch (IOException e) {
            throw new UncheckedIOException("读取区块载荷失败 (" + localX + "," + localZ + ")", e);
        }
    }

    private static byte[] decompress(byte[] payload, NbtReader.Compression compression) {
        try {
            java.io.InputStream in = switch (compression) {
                case GZIP -> new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(payload));
                case ZLIB -> new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(payload));
                case NONE -> new java.io.ByteArrayInputStream(payload);
            };
            try (in) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("区块解压失败", e);
        }
    }

    public static ChunkPos chunkPos(int regionX, int regionZ, int localX, int localZ) {
        return new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** region 内一个存在区块的轻量引用。 */
    public record ChunkEntry(int localX, int localZ, int timestampSeconds) {
    }
}
