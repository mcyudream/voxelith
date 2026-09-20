package online.yudream.voxelith.tile.infrastructure.meshopt;

import java.util.Arrays;

/**
 * meshoptimizer 顶点编解码（vertex codec v1）的纯 Java 移植，与
 * {@code meshoptimizer/src/vertexcodec.cpp} 的线格式逐字节一致。
 *
 * <p>为什么要有这一份：{@code EXT_meshopt_compression} 的熵编码此前被 ADR 0004 判为
 * 「需要 JNI 或构建期 CLI」。这份移植把编码器收进仓库——不需要本地 C++ 工具链，
 * 也不在运行时引入 native 依赖；解码器同时用于单测往返校验与瓦片自检。</p>
 *
 * <p>线格式（与官方实现一致，不可自行更改）：</p>
 * <ol>
 *   <li>1 字节头 {@code 0xa0 | version}；</li>
 *   <li>若顶点数 &gt; 0，按固定块大小 {@code min(256, (8192/顶点字节数) 向下对齐 16)} 分块；
 *       v1 每块先写 {@code 顶点字节数/4} 个控制字节（每字节 2 bit，对应 4 个字节通道的编码方式），
 *       再逐字节通道写 delta + 熵编码数据；</li>
 *   <li>末尾补齐（v0 至少 32 字节、v1 至少 24 字节）后写「首顶点」原样字节，v1 再补通道表。</li>
 * </ol>
 *
 * <p><b>版本选择走的是现实约束而非偏好</b>：three.js 内置的
 * {@code examples/jsm/libs/meshopt_decoder.module.js}（meshoptimizer 0.22 构建）只认顶点流 v0，
 * 收到 {@code 0xa1} 直接返回 -1。所以瓦片默认走 {@link #DEFAULT_VERSION v0}——少一组控制字节、
 * 压缩率略低，但所有解码器都认。v1 编码能力保留，由
 * {@link #encode(byte[], int, int, int)} 显式选择（需 meshoptimizer ≥ 0.23 的解码器）。</p>
 *
 * <p>熵编码（{@code encodeBytes}）以 16 字节为一组，每组可选 0/1/2/4/8 位定长打包 + 溢出字节，
 * 组选择位存进 2 bit/组的头；「字节通道」维度上是按 1/2/4 字节的 delta（或 XOR 旋转）预测，
 * 这正是网格数据能压到 1/2~1/3 的来源。</p>
 */
public final class MeshoptVertexCodec {

    /** 顶点流头字节（高 4 位固定）。 */
    private static final int HEADER = 0xA0;

    /** 解码端支持的最高版本。 */
    private static final int MAX_VERSION = 1;

    /** 默认编码版本：0 —— three.js 内置的 meshopt 解码器（meshoptimizer 0.22）只认 v0。 */
    public static final int DEFAULT_VERSION = 0;

    private static final int BLOCK_SIZE_BYTES = 8192;
    private static final int BLOCK_MAX_SIZE = 256;
    private static final int GROUP_SIZE = 16;
    private static final int TAIL_MIN_SIZE_V0 = 32;
    private static final int TAIL_MIN_SIZE_V1 = 24;

    /** 编码档位：2 = 默认（与 {@code kEncodeDefaultLevel} 一致）。 */
    private static final int LEVEL = 2;

    /** v0 的位宽档位（{@code kBitsV0}）。 */
    private static final int[] BITS_V0 = {0, 2, 4, 8};

    /** {@code kBitsV1 + ctrl}：ctrl=0 → 0/1/2/4 位，ctrl=1 → 1/2/4/8 位。 */
    private static final int[] BITS_CTRL0 = {0, 1, 2, 4};
    private static final int[] BITS_CTRL1 = {1, 2, 4, 8};

    /** 通道估计时的采样块跳过数（{@code block_skip}）。 */
    private static final int BLOCK_SKIP = 3;

    private MeshoptVertexCodec() {
    }

    // -----------------------------------------------------------------------
    // 公开 API
    // -----------------------------------------------------------------------

    /** 编码后的长度上界（与 {@code meshopt_encodeVertexBufferBound} 同式）。 */
    public static int encodeBound(int vertexCount, int vertexSize) {
        return encodeBound(vertexCount, vertexSize, DEFAULT_VERSION);
    }

    public static int encodeBound(int vertexCount, int vertexSize, int version) {
        requireVertexLayout(vertexSize);
        requireVersion(version);
        int blockSize = blockSize(vertexSize);
        int blocks = (vertexCount + blockSize - 1) / blockSize;
        int byteHeader = (blockSize / GROUP_SIZE + 3) / 4;
        int tail = Math.max(tailSize(vertexSize, version), tailMinSize(version));
        int control = version == 0 ? 0 : vertexSize / 4;
        return 1 + blocks * (control + vertexSize * (byteHeader + blockSize)) + tail;
    }

    /**
     * 顶点缓冲压缩（默认 v0，three.js 内置解码器可解）。
     *
     * @param vertices    顶点字节（行主序：顶点 i 占 {@code [i*vertexSize, (i+1)*vertexSize)}）
     * @param vertexCount 顶点数
     * @param vertexSize  每顶点字节数（须为 4 的倍数且 ≤ 256）
     */
    public static byte[] encode(byte[] vertices, int vertexCount, int vertexSize) {
        return encode(vertices, vertexCount, vertexSize, DEFAULT_VERSION);
    }

    /** 指定顶点流版本（0 = 通用；1 = 控制字节 + 通道表，需较新的解码器）。 */
    public static byte[] encode(byte[] vertices, int vertexCount, int vertexSize, int version) {
        requireVertexLayout(vertexSize);
        requireVersion(version);
        if (vertexCount < 0) {
            throw new IllegalArgumentException("顶点数不能为负: " + vertexCount);
        }
        if ((long) vertexCount * vertexSize > vertices.length) {
            throw new IllegalArgumentException("顶点数据不足: 需要 " + ((long) vertexCount * vertexSize)
                    + " 字节，收到 " + vertices.length);
        }

        byte[] out = new byte[encodeBound(vertexCount, vertexSize, version)];
        int pos = 0;
        out[pos++] = (byte) (HEADER | version);

        byte[] firstVertex = new byte[vertexSize];
        if (vertexCount > 0) {
            System.arraycopy(vertices, 0, firstVertex, 0, vertexSize);
        }
        byte[] lastVertex = firstVertex.clone();

        int blockSize = blockSize(vertexSize);
        byte[] channels = new byte[Math.max(64, vertexSize / 4)];
        if (version != 0 && vertexCount > 1) {
            estimateChannels(vertices, vertexCount, vertexSize, blockSize, channels);
        }

        byte[] scratch = new byte[BLOCK_MAX_SIZE];
        int offset = 0;
        while (offset < vertexCount) {
            int count = Math.min(blockSize, vertexCount - offset);
            pos = encodeBlock(out, pos, vertices, offset * vertexSize, count, vertexSize,
                    lastVertex, channels, version, scratch);
            offset += count;
        }

        int tailSize = tailSize(vertexSize, version);
        int tailPad = Math.max(tailSize, tailMinSize(version));
        for (int i = tailSize; i < tailPad; i++) {
            out[pos++] = 0;
        }
        System.arraycopy(firstVertex, 0, out, pos, vertexSize);
        pos += vertexSize;
        if (version != 0) {
            System.arraycopy(channels, 0, out, pos, vertexSize / 4);
            pos += vertexSize / 4;
        }

        return Arrays.copyOf(out, pos);
    }

    /** 顶点流版本；非法数据返回 -1。 */
    public static int decodeVersion(byte[] data) {
        if (data.length < 1 || (data[0] & 0xF0) != HEADER) {
            return -1;
        }
        int version = data[0] & 0x0F;
        return version > MAX_VERSION ? -1 : version;
    }

    /**
     * 顶点缓冲解压（{@code meshopt_decodeVertexBuffer} 等价实现）。
     *
     * @return {@code vertexCount * vertexSize} 字节的顶点数据
     * @throws IllegalArgumentException 数据非法
     */
    public static byte[] decode(byte[] data, int vertexCount, int vertexSize) {
        requireVertexLayout(vertexSize);
        if (vertexCount < 0) {
            throw new IllegalArgumentException("顶点数不能为负: " + vertexCount);
        }
        int version = decodeVersion(data);
        if (version < 0) {
            throw new IllegalArgumentException("不是合法的 meshopt 顶点流");
        }

        // 尾部：首顶点（预测初值）+ 通道表；前面的若干字节是对齐填充
        int tailSize = tailSize(vertexSize, version);
        int tailPad = Math.max(tailSize, tailMinSize(version));
        if (data.length < 1 + tailPad) {
            throw new IllegalArgumentException("meshopt 顶点流尾部截断");
        }
        int tail = data.length - tailSize;
        byte[] lastVertex = Arrays.copyOfRange(data, tail, tail + vertexSize);
        byte[] channels = version == 0
                ? new byte[vertexSize / 4]
                : Arrays.copyOfRange(data, tail + vertexSize, tail + vertexSize + vertexSize / 4);

        int pos = 1;
        int dataEnd = data.length - tailPad;
        byte[] out = new byte[vertexCount * vertexSize];
        byte[] scratch = new byte[BLOCK_MAX_SIZE * 4];
        byte[] transposed = new byte[BLOCK_MAX_SIZE * vertexSize];

        int blockSize = blockSize(vertexSize);
        int offset = 0;
        while (offset < vertexCount) {
            int count = Math.min(blockSize, vertexCount - offset);
            pos = decodeBlock(data, pos, dataEnd, out, offset * vertexSize, count, vertexSize,
                    lastVertex, channels, version, scratch, transposed);
            offset += count;
        }
        if (pos != dataEnd) {
            throw new IllegalArgumentException("meshopt 顶点流长度不符: 读完 " + pos + "，期望 " + dataEnd);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // 编码
    // -----------------------------------------------------------------------

    private static int encodeBlock(byte[] out, int pos, byte[] vertexData, int vertexBase, int count,
                                   int vertexSize, byte[] lastVertex, byte[] channels, int version,
                                   byte[] scratch) {
        int aligned = align16(count);
        int controlSize = version == 0 ? 0 : vertexSize / 4;
        int controlPos = pos;
        pos += controlSize;
        Arrays.fill(out, controlPos, controlPos + controlSize, (byte) 0);

        for (int k = 0; k < vertexSize; k++) {
            Arrays.fill(scratch, 0, aligned, (byte) 0);
            encodeDeltas(scratch, vertexData, vertexBase, count, vertexSize, lastVertex, k,
                    version == 0 ? 0 : channels[k / 4] & 0xFF);

            int ctrl = version == 0 ? 0 : estimateControl(scratch, count, aligned, LEVEL);
            if (version != 0) {
                out[controlPos + k / 4] |= (byte) (ctrl << ((k % 4) * 2));
            }

            if (ctrl == 3) {
                System.arraycopy(scratch, 0, out, pos, count);
                pos += count;
            } else if (ctrl != 2) {
                pos = encodeBytes(out, pos, scratch, aligned,
                        version == 0 ? BITS_V0 : (ctrl == 1 ? BITS_CTRL1 : BITS_CTRL0));
            }
        }

        System.arraycopy(vertexData, vertexBase + (count - 1) * vertexSize, lastVertex, 0, vertexSize);
        return pos;
    }

    /**
     * 通道选择（{@code estimateChannel}，level=2 档：只在「每字节 delta」与「每 2 字节 delta」之间选）。
     * 通道表写进流尾，解码端据此选预测方式。
     */
    private static void estimateChannels(byte[] vertexData, int vertexCount, int vertexSize,
                                         int blockSize, byte[] channels) {
        byte[] block = new byte[BLOCK_MAX_SIZE];
        byte[] lastVertex = new byte[Math.max(256, vertexSize)];
        int sampleStride = blockSize * BLOCK_SKIP;

        for (int k = 0; k + 4 <= vertexSize; k += 4) {
            long[] sizes = new long[2];
            for (int i = 0; i < vertexCount; i += sampleStride) {
                int blockCount = Math.min(blockSize, vertexCount - i);
                int aligned = align16(blockCount);
                int previous = i == 0 ? 0 : (i - 1) * vertexSize;
                System.arraycopy(vertexData, previous, lastVertex, 0, vertexSize);

                for (int channel = 0; channel < 2; channel++) {
                    for (int j = 0; j < 4; j++) {
                        Arrays.fill(block, 0, aligned, (byte) 0);
                        encodeDeltas(block, vertexData, i * vertexSize, blockCount, vertexSize,
                                lastVertex, k + j, channel);
                        for (int ig = 0; ig < blockCount; ig += GROUP_SIZE) {
                            sizes[channel] += bestGroupSize(block, ig);
                        }
                    }
                }
            }
            channels[k / 4] = (byte) (sizes[1] < sizes[0] ? 1 : 0);
        }
    }

    private static long bestGroupSize(byte[] block, int base) {
        long best = measure(block, base, 1);
        best = Math.min(best, measure(block, base, 2));
        best = Math.min(best, measure(block, base, 4));
        best = Math.min(best, measure(block, base, 8));
        return best;
    }

    /**
     * 逐字节通道的 delta 预测。{@code channel & 3} = 0/1 时按 1/2 字节小端整数做 zigzag 差值；
     * 2 时按 4 字节做 XOR + 旋转（浮点数据友好）。
     */
    private static void encodeDeltas(byte[] buffer, byte[] vertexData, int vertexBase, int vertexCount,
                                     int vertexSize, byte[] lastVertex, int k, int channel) {
        int type = channel & 3;
        int width = switch (type) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> throw new IllegalArgumentException("非法通道编码: " + channel);
        };
        int rot = channel >> 4;
        int k0 = k & ~(width - 1);
        int shift = (k & (width - 1)) * 8;

        long previous = readLittleEndian(lastVertex, k0, width);
        int vertex = vertexBase + k0;
        for (int i = 0; i < vertexCount; i++) {
            long value = readLittleEndian(vertexData, vertex, width);
            long delta = type == 2
                    ? rotate32(value ^ previous, rot)
                    : zigzag(value - previous, width);
            buffer[i] = (byte) ((delta >>> shift) & 0xFF);
            previous = value;
            vertex += vertexSize;
        }
    }

    /** {@code encodeBytes}：16 字节一组，2 bit 组头 + 定长/溢出字节。 */
    private static int encodeBytes(byte[] out, int pos, byte[] buffer, int bufferSize, int[] bits) {
        int headerSize = (bufferSize / GROUP_SIZE + 3) / 4;
        int header = pos;
        pos += headerSize;
        Arrays.fill(out, header, header + headerSize, (byte) 0);

        int lastBits = -1;
        for (int i = 0; i < bufferSize; i += GROUP_SIZE) {
            int bestBitk = 3;
            long bestSize = measure(buffer, i, bits[bestBitk]);
            for (int bitk = 0; bitk < 3; bitk++) {
                long size = measure(buffer, i, bits[bitk]);
                // 同样大小时倾向与上一组保持同一位宽，但绝不因此放弃字面量编码
                if (size < bestSize
                        || (size == bestSize && bits[bitk] == lastBits && bits[bestBitk] != 8)) {
                    bestBitk = bitk;
                    bestSize = size;
                }
            }
            int group = i / GROUP_SIZE;
            out[header + group / 4] |= (byte) (bestBitk << ((group % 4) * 2));
            pos = encodeGroup(out, pos, buffer, i, bits[bestBitk]);
            lastBits = bits[bestBitk];
        }
        return pos;
    }

    private static int encodeGroup(byte[] out, int pos, byte[] buffer, int base, int bits) {
        if (bits == 0) {
            return pos;
        }
        if (bits == 8) {
            System.arraycopy(buffer, base, out, pos, GROUP_SIZE);
            return pos + GROUP_SIZE;
        }
        int perByte = 8 / bits;
        int sentinel = (1 << bits) - 1;
        for (int i = 0; i < GROUP_SIZE; i += perByte) {
            int packed = 0;
            for (int k = 0; k < perByte; k++) {
                int value = buffer[base + i + k] & 0xFF;
                packed = (packed << bits) | Math.min(value, sentinel);
            }
            if (bits == 1) {
                packed = reverseBits8(packed);
            }
            out[pos++] = (byte) packed;
        }
        for (int i = 0; i < GROUP_SIZE; i++) {
            int value = buffer[base + i] & 0xFF;
            if (value >= sentinel) {
                out[pos++] = (byte) value;
            }
        }
        return pos;
    }

    private static int estimateControl(byte[] buffer, int vertexCount, int aligned, int level) {
        if (controlZero(buffer, aligned)) {
            return 2;
        }
        if (level == 0) {
            return 1;
        }
        int headerSize = (aligned / GROUP_SIZE + 3) / 4;
        long est0 = headerSize;
        long est1 = headerSize;
        for (int i = 0; i < aligned; i += GROUP_SIZE) {
            long size0 = measure(buffer, i, 0);
            long size1 = measure(buffer, i, 1);
            long size2 = measure(buffer, i, 2);
            long size4 = measure(buffer, i, 4);
            long size8 = measure(buffer, i, 8);
            long size124 = Math.min(Math.min(size1, size2), size4);
            est0 += Math.min(size124, size0);
            est1 += Math.min(size124, size8);
        }
        if (est0 < vertexCount || est1 < vertexCount) {
            return est0 < est1 ? 0 : 1;
        }
        return 3;
    }

    private static boolean controlZero(byte[] buffer, int aligned) {
        int end = Math.min(aligned, buffer.length);
        for (int i = 0; i < end; i += GROUP_SIZE) {
            if (!groupZero(buffer, i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean groupZero(byte[] buffer, int base) {
        if (base + GROUP_SIZE > buffer.length) {
            return false;
        }
        for (int i = 0; i < GROUP_SIZE; i++) {
            if (buffer[base + i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** 单组编码代价；不可用（0 位但非全零）返回很大的值。 */
    private static long measure(byte[] buffer, int base, int bits) {
        if (bits == 0) {
            return groupZero(buffer, base) ? 0 : Long.MAX_VALUE / 8;
        }
        if (bits == 8) {
            return GROUP_SIZE;
        }
        long result = (long) GROUP_SIZE * bits / 8;
        int sentinel = (1 << bits) - 1;
        for (int i = 0; i < GROUP_SIZE; i++) {
            if ((buffer[base + i] & 0xFF) >= sentinel) {
                result++;
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // 解码
    // -----------------------------------------------------------------------

    private static int decodeBlock(byte[] data, int pos, int dataEnd, byte[] out, int outBase, int count,
                                   int vertexSize, byte[] lastVertex, byte[] channels, int version,
                                   byte[] scratch, byte[] transposed) {
        int aligned = align16(count);
        int controlSize = version == 0 ? 0 : vertexSize / 4;
        if (pos + controlSize > dataEnd) {
            throw new IllegalArgumentException("meshopt 顶点流控制字节截断");
        }
        int controlPos = pos;
        pos += controlSize;

        int transposedBase = 0;
        for (int k = 0; k < vertexSize; k += 4) {
            int control = version == 0 ? 0 : data[controlPos + k / 4] & 0xFF;
            for (int j = 0; j < 4; j++) {
                int ctrl = (control >> (j * 2)) & 3;
                int laneBase = j * count;
                if (ctrl == 3) {
                    if (pos + count > dataEnd) {
                        throw new IllegalArgumentException("meshopt 顶点流字面量截断");
                    }
                    System.arraycopy(data, pos, scratch, laneBase, count);
                    pos += count;
                } else if (ctrl == 2) {
                    Arrays.fill(scratch, laneBase, laneBase + count, (byte) 0);
                } else {
                    pos = decodeBytes(data, pos, dataEnd, scratch, laneBase, aligned,
                            version == 0 ? BITS_V0 : (ctrl == 1 ? BITS_CTRL1 : BITS_CTRL0));
                    // 对齐后的尾巴不参与重建，清零避免影响后续匹配
                    Arrays.fill(scratch, laneBase + count, laneBase + aligned, (byte) 0);
                }
            }
            decodeDeltas(scratch, transposed, transposedBase, count, vertexSize, lastVertex, k,
                    version == 0 ? 0 : channels[k / 4] & 0xFF);
            transposedBase += 4;
        }

        System.arraycopy(transposed, 0, out, outBase, count * vertexSize);
        System.arraycopy(out, outBase + (count - 1) * vertexSize, lastVertex, 0, vertexSize);
        return pos;
    }

    private static int decodeBytes(byte[] data, int pos, int dataEnd, byte[] buffer, int base,
                                   int bufferSize, int[] bits) {
        int headerSize = (bufferSize / GROUP_SIZE + 3) / 4;
        if (pos + headerSize > dataEnd) {
            throw new IllegalArgumentException("meshopt 顶点流熵头截断");
        }
        int header = pos;
        pos += headerSize;
        for (int i = 0; i < bufferSize; i += GROUP_SIZE) {
            int group = i / GROUP_SIZE;
            int bitsk = (data[header + group / 4] >> ((group % 4) * 2)) & 3;
            pos = decodeGroup(data, pos, dataEnd, buffer, base + i, bits[bitsk]);
        }
        return pos;
    }

    private static int decodeGroup(byte[] data, int pos, int dataEnd, byte[] buffer, int base, int bits) {
        switch (bits) {
            case 0 -> {
                Arrays.fill(buffer, base, base + GROUP_SIZE, (byte) 0);
                return pos;
            }
            case 8 -> {
                if (pos + GROUP_SIZE > dataEnd) {
                    throw new IllegalArgumentException("meshopt 顶点流截断");
                }
                System.arraycopy(data, pos, buffer, base, GROUP_SIZE);
                return pos + GROUP_SIZE;
            }
            default -> {
                // 定长位段 + 溢出字节（哨兵值）
            }
        }
        int perByte = 8 / bits;
        int sentinel = (1 << bits) - 1;
        int fixedBytes = GROUP_SIZE / perByte;
        int variable = pos + fixedBytes;
        if (variable > dataEnd) {
            throw new IllegalArgumentException("meshopt 顶点流截断");
        }
        int out = base;
        for (int i = 0; i < fixedBytes; i++) {
            int packed = data[pos + i] & 0xFF;
            if (bits == 1) {
                packed = reverseBits8(packed);
            }
            for (int k = 0; k < perByte; k++) {
                int enc = (packed >>> (8 - bits)) & sentinel;
                packed = (packed << bits) & 0xFF;
                if (enc == sentinel) {
                    if (variable >= dataEnd) {
                        throw new IllegalArgumentException("meshopt 顶点流溢出字节截断");
                    }
                    buffer[out++] = data[variable++];
                } else {
                    buffer[out++] = (byte) enc;
                }
            }
        }
        return variable;
    }

    private static void decodeDeltas(byte[] scratch, byte[] transposed, int transposedBase, int count,
                                     int vertexSize, byte[] lastVertex, int k, int channel) {
        int type = channel & 3;
        int width = switch (type) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> throw new IllegalArgumentException("非法通道编码: " + channel);
        };
        int rot = (32 - (channel >> 4)) & 31;

        for (int lane = 0; lane < 4; lane += width) {
            long previous = readLittleEndian(lastVertex, k + lane, width);
            int bufferBase = (lane / width) * count * width;
            int target = transposedBase + lane;
            for (int i = 0; i < count; i++) {
                long raw = 0;
                for (int j = 0; j < width; j++) {
                    raw |= (long) (scratch[bufferBase + i + count * j] & 0xFF) << (8 * j);
                }
                long value = type == 2
                        ? (rotate32(raw, rot) ^ previous) & mask32(width)
                        : (unzigzag(raw, width) + previous) & mask32(width);
                writeLittleEndian(transposed, target, value, width);
                previous = value;
                target += vertexSize;
            }
        }
    }

    // -----------------------------------------------------------------------
    // 位/字节工具
    // -----------------------------------------------------------------------

    private static void requireVertexLayout(int vertexSize) {
        if (vertexSize <= 0 || vertexSize > 256 || vertexSize % 4 != 0) {
            throw new IllegalArgumentException("顶点字节数须为 4 的倍数且 ≤ 256，收到: " + vertexSize);
        }
    }

    private static void requireVersion(int version) {
        if (version < 0 || version > MAX_VERSION) {
            throw new IllegalArgumentException("不支持的顶点流版本: " + version);
        }
    }

    private static int tailSize(int vertexSize, int version) {
        return version == 0 ? vertexSize : vertexSize + vertexSize / 4;
    }

    private static int tailMinSize(int version) {
        return version == 0 ? TAIL_MIN_SIZE_V0 : TAIL_MIN_SIZE_V1;
    }

    private static int blockSize(int vertexSize) {
        int result = (BLOCK_SIZE_BYTES / vertexSize) & ~(GROUP_SIZE - 1);
        return Math.min(result, BLOCK_MAX_SIZE);
    }

    private static int align16(int value) {
        return (value + GROUP_SIZE - 1) & ~(GROUP_SIZE - 1);
    }

    private static int mask32(int width) {
        return switch (width) {
            case 1 -> 0xFF;
            case 2 -> 0xFFFF;
            default -> -1;
        };
    }

    private static long readLittleEndian(byte[] data, int base, int width) {
        long value = 0;
        for (int j = 0; j < width; j++) {
            value |= (long) (data[base + j] & 0xFF) << (8 * j);
        }
        return value;
    }

    private static void writeLittleEndian(byte[] data, int base, long value, int width) {
        for (int j = 0; j < width; j++) {
            data[base + j] = (byte) ((value >>> (8 * j)) & 0xFF);
        }
    }

    /** {@code zigzag}：把有符号差值折到无符号域（参考实现按 T 的位宽回绕）。 */
    private static long zigzag(long value, int width) {
        int bits = width * 8;
        long masked = value & mask32(width);
        long sign = (masked >>> (bits - 1)) & 1;
        return ((masked << 1) ^ (0 - sign)) & mask32(width);
    }

    private static long unzigzag(long value, int width) {
        return ((0 - (value & 1)) ^ (value >>> 1)) & mask32(width);
    }

    private static long rotate32(long value, int bits) {
        int v = (int) value;
        int r = bits & 31;
        if (r == 0) {
            return v & 0xFFFFFFFFL;
        }
        return ((v << r) | (v >>> (32 - r))) & 0xFFFFFFFFL;
    }

    /** 8 bit 位序反转（编码 1 位组时按位反转写出，解码同样先反转）。 */
    private static int reverseBits8(int value) {
        return (int) ((((value & 0xFFL) * 0x80200802L) & 0x0884422110L) * 0x0101010101L >>> 32) & 0xFF;
    }
}
