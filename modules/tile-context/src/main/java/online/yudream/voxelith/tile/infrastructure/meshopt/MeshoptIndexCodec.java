package online.yudream.voxelith.tile.infrastructure.meshopt;

/**
 * meshoptimizer 索引编解码（index codec v1）的纯 Java 移植，与
 * {@code meshoptimizer/src/indexcodec.cpp} 的线格式逐字节一致。
 *
 * <p>三角面片流：1 字节头 {@code 0xe0 | version}，随后每三角 1 字节「代码」，
 * 代码区之后是变长数据区（自由顶点索引的 zigzag 变长整数增量），最后 16 字节 codeaux 表
 * （同时充当解码时「每三角最多 16 字节」的安全边界）。</p>
 *
 * <p>压缩原理是「边 FIFO + 顶点 FIFO 预测」：绝大多数相邻三角共享一条边与一个新顶点，
 * 于是每三角只花 1 字节代码；剩下的自由索引才写增量。这是索引流能压到
 * 约 1 字节/三角的原因，与顶点流的熵编码是两套独立算法。</p>
 */
public final class MeshoptIndexCodec {

    /** 索引流头字节（高 4 位固定）。 */
    private static final int HEADER = 0xE0;

    /** {@code kSequenceHeader}：索引序列（非三角）头，仅解码时识别。 */
    private static final int SEQUENCE_HEADER = 0xD0;

    private static final int VERSION = 1;

    private static final int[] ROTATIONS = {0, 1, 2, 0, 1};

    /** 官方训练集上统计出的 codeaux 编码表（最后两项不用于编码）。 */
    private static final int[] CODE_AUX_TABLE = {
            0x00, 0x76, 0x87, 0x56, 0x67, 0x78, 0xa9, 0x86,
            0x65, 0x89, 0x68, 0x98, 0x01, 0x69, 0x00, 0x00,
    };

    private static final int FIFO_SIZE = 16;

    /** version ≥ 1 时顶点 FIFO 可引用的最大槽位（13/14 留给「last±1」）。 */
    private static final int FEC_MAX = 13;

    private MeshoptIndexCodec() {
    }

    /** 编码长度上界（与 {@code meshopt_encodeIndexBufferBound} 同式）。 */
    public static int encodeBound(int indexCount, int vertexCount) {
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("索引数须为 3 的倍数，收到: " + indexCount);
        }
        int vertexBits = 1;
        while (vertexBits < 32 && (long) vertexCount > (1L << vertexBits)) {
            vertexBits++;
        }
        int vertexGroups = (vertexBits + 1 + 6) / 7;
        return 1 + (indexCount / 3) * (2 + 3 * vertexGroups) + 16;
    }

    /**
     * 三角索引压缩。
     *
     * @param indices    索引数组（长度须为 3 的倍数）
     * @param vertexCount 顶点总数（用于估算变长整数位宽）
     */
    public static byte[] encode(int[] indices, int vertexCount) {
        int indexCount = indices.length;
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("索引数须为 3 的倍数，收到: " + indexCount);
        }
        // 变长整数位宽要按「实际出现过的最大索引」估算，否则越界写入
        int maxIndex = 0;
        for (int index : indices) {
            maxIndex = Math.max(maxIndex, index);
        }
        byte[] out = new byte[encodeBound(indexCount, Math.max(vertexCount, maxIndex + 1))];
        int pos = 0;
        out[pos++] = (byte) (HEADER | VERSION);

        int[] edgeFifo = new int[FIFO_SIZE * 2];
        java.util.Arrays.fill(edgeFifo, -1);
        int[] vertexFifo = new int[FIFO_SIZE];
        java.util.Arrays.fill(vertexFifo, -1);
        int edgeOffset = 0;
        int vertexOffset = 0;

        int next = 0;
        int last = 0;

        int code = pos;
        int codeEnd = code + indexCount / 3;
        int data = codeEnd;
        pos = codeEnd;

        for (int i = 0; i < indexCount; i += 3) {
            int fer = getEdgeFifo(edgeFifo, indices[i], indices[i + 1], indices[i + 2], edgeOffset);

            if (fer >= 0 && (fer >> 2) < 15) {
                int order = fer & 3;
                int a = indices[i + ROTATIONS[order]];
                int b = indices[i + ROTATIONS[order + 1]];
                int c = indices[i + ROTATIONS[order + 2]];

                int fe = fer >> 2;
                int fc = getVertexFifo(vertexFifo, c, vertexOffset);

                int fec;
                if (fc >= 1 && fc < FEC_MAX) {
                    fec = fc;
                } else if (c == next) {
                    fec = 0;
                    next++;
                } else {
                    fec = 15;
                }
                if (fec == 15) {
                    // 条带式序列：c 紧邻上一个自由索引时用 13/14 少写一次增量
                    if (c + 1 == last) {
                        fec = 13;
                        last = c;
                    } else if (c == last + 1) {
                        fec = 14;
                        last = c;
                    }
                }

                out[code++] = (byte) ((fe << 4) | fec);

                if (fec == 15) {
                    data = writeIndex(out, data, c, last);
                    last = c;
                }
                if (fec == 0 || fec >= FEC_MAX) {
                    vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, 1);
                }
                edgeOffset = pushEdgeFifo(edgeFifo, c, b, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, a, c, edgeOffset);
            } else {
                int rotation = rotateTriangle(indices[i], indices[i + 1], indices[i + 2], next);
                int a = indices[i + ROTATIONS[rotation]];
                int b = indices[i + ROTATIONS[rotation + 1]];
                int c = indices[i + ROTATIONS[rotation + 2]];

                boolean reset = false;
                if (a == 0 && b == 1 && c == 2 && next > 0) {
                    reset = true;
                    next = 0;
                    java.util.Arrays.fill(vertexFifo, -1);
                }

                int fb = getVertexFifo(vertexFifo, b, vertexOffset);
                int fc = getVertexFifo(vertexFifo, c, vertexOffset);

                int fea;
                if (a == next) {
                    fea = 0;
                    next++;
                } else {
                    fea = 15;
                }
                int feb;
                if (fb >= 0 && fb < 14) {
                    feb = fb + 1;
                } else if (b == next) {
                    feb = 0;
                    next++;
                } else {
                    feb = 15;
                }
                int fec;
                if (fc >= 0 && fc < 14) {
                    fec = fc + 1;
                } else if (c == next) {
                    fec = 0;
                    next++;
                } else {
                    fec = 15;
                }

                int codeAux = (feb << 4) | fec;
                int codeAuxIndex = indexOfCodeAux(codeAux);

                if (fea == 0 && codeAuxIndex >= 0 && codeAuxIndex < 14 && !reset) {
                    out[code++] = (byte) ((15 << 4) | codeAuxIndex);
                } else {
                    out[code++] = (byte) ((15 << 4) | 14 | fea);
                    out[data++] = (byte) codeAux;
                }

                if (fea == 15) {
                    data = writeIndex(out, data, a, last);
                    last = a;
                }
                if (feb == 15) {
                    data = writeIndex(out, data, b, last);
                    last = b;
                }
                if (fec == 15) {
                    data = writeIndex(out, data, c, last);
                    last = c;
                }

                if (fea == 0 || fea == 15) {
                    vertexOffset = pushVertexFifo(vertexFifo, a, vertexOffset, 1);
                }
                if (feb == 0 || feb == 15) {
                    vertexOffset = pushVertexFifo(vertexFifo, b, vertexOffset, 1);
                }
                if (fec == 0 || fec == 15) {
                    vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, 1);
                }

                edgeOffset = pushEdgeFifo(edgeFifo, b, a, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, c, b, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, a, c, edgeOffset);
            }
        }

        if (code != codeEnd) {
            throw new IllegalStateException("索引流代码区长度不符");
        }
        // codeaux 表写在流尾，同时是解码端的安全边界
        for (int i = 0; i < 16; i++) {
            out[data++] = (byte) CODE_AUX_TABLE[i];
        }
        byte[] result = new byte[data];
        System.arraycopy(out, 0, result, 0, data);
        return result;
    }

    /** 索引流版本；非法数据返回 -1。 */
    public static int decodeVersion(byte[] data) {
        if (data.length < 1) {
            return -1;
        }
        int header = data[0] & 0xF0;
        if (header != HEADER && header != SEQUENCE_HEADER) {
            return -1;
        }
        int version = data[0] & 0x0F;
        return version > VERSION ? -1 : version;
    }

    /**
     * 三角索引解压（{@code meshopt_decodeIndexBuffer} 等价实现）。
     * 本移植只接受三角（kIndexHeader）流。
     */
    public static int[] decode(byte[] data, int indexCount) {
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("索引数须为 3 的倍数，收到: " + indexCount);
        }
        if (data.length < 1 + indexCount / 3 + 16) {
            throw new IllegalArgumentException("meshopt 索引流过短");
        }
        if ((data[0] & 0xF0) != HEADER) {
            throw new IllegalArgumentException("不是合法的 meshopt 三角索引流");
        }
        int version = data[0] & 0x0F;
        if (version > VERSION) {
            throw new IllegalArgumentException("不支持的索引流版本: " + version);
        }
        int fecMax = version >= 1 ? FEC_MAX : 15;

        int[] edgeFifo = new int[FIFO_SIZE * 2];
        java.util.Arrays.fill(edgeFifo, -1);
        int[] vertexFifo = new int[FIFO_SIZE];
        java.util.Arrays.fill(vertexFifo, -1);
        int edgeOffset = 0;
        int vertexOffset = 0;

        int next = 0;
        int last = 0;

        int code = 1;
        int codeEnd = code + indexCount / 3;
        int dataPos = codeEnd;
        int dataSafeEnd = data.length - 16;
        int tablePos = dataSafeEnd;

        int[] out = new int[indexCount];
        int outPos = 0;

        while (code < codeEnd) {
            int codeTri = data[code++] & 0xFF;
            if (codeTri < 0xF0) {
                int fe = codeTri >> 4;
                int edge = (edgeOffset - 1 - fe) & 15;
                int a = edgeFifo[edge * 2];
                int b = edgeFifo[edge * 2 + 1];
                int c;
                int fec = codeTri & 15;
                if (fec < fecMax) {
                    int cf = vertexFifo[(vertexOffset - 1 - fec) & 15];
                    c = fec == 0 ? next : cf;
                    if (fec == 0) {
                        next++;
                    }
                    vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, fec == 0 ? 1 : 0);
                } else {
                    if (dataPos > dataSafeEnd) {
                        throw new IllegalArgumentException("meshopt 索引流越界");
                    }
                    if (fec != 15) {
                        c = last + (fec * 2 - 27);
                    } else {
                        int[] read = readIndex(data, dataPos, last);
                        c = read[0];
                        dataPos = read[1];
                    }
                    last = c;
                    vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, 1);
                }
                edgeOffset = pushEdgeFifo(edgeFifo, c, b, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, a, c, edgeOffset);

                out[outPos++] = a;
                out[outPos++] = b;
                out[outPos++] = c;
            } else if (codeTri < 0xFE) {
                int codeAux = data[tablePos + (codeTri & 15)] & 0xFF;
                int feb = codeAux >> 4;
                int fec = codeAux & 15;

                int a = next++;
                int b;
                if (feb == 0) {
                    b = next++;
                } else {
                    b = vertexFifo[(vertexOffset - feb) & 15];
                }
                int c;
                if (fec == 0) {
                    c = next++;
                } else {
                    c = vertexFifo[(vertexOffset - fec) & 15];
                }

                out[outPos++] = a;
                out[outPos++] = b;
                out[outPos++] = c;

                vertexOffset = pushVertexFifo(vertexFifo, a, vertexOffset, 1);
                vertexOffset = pushVertexFifo(vertexFifo, b, vertexOffset, feb == 0 ? 1 : 0);
                vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, fec == 0 ? 1 : 0);

                edgeOffset = pushEdgeFifo(edgeFifo, b, a, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, c, b, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, a, c, edgeOffset);
            } else {
                if (dataPos > dataSafeEnd) {
                    throw new IllegalArgumentException("meshopt 索引流越界");
                }
                int codeAux = data[dataPos++] & 0xFF;
                int fea = codeTri == 0xFE ? 0 : 15;
                int feb = codeAux >> 4;
                int fec = codeAux & 15;

                if (codeAux == 0) {
                    next = 0;
                }

                int a;
                if (fea == 0) {
                    a = next++;
                } else {
                    a = 0;
                }
                int b = feb == 0 ? next++ : vertexFifo[(vertexOffset - feb) & 15];
                int c = fec == 0 ? next++ : vertexFifo[(vertexOffset - fec) & 15];

                if (fea == 15) {
                    int[] read = readIndex(data, dataPos, last);
                    a = read[0];
                    dataPos = read[1];
                    last = a;
                }
                if (feb == 15) {
                    int[] read = readIndex(data, dataPos, last);
                    b = read[0];
                    dataPos = read[1];
                    last = b;
                }
                if (fec == 15) {
                    int[] read = readIndex(data, dataPos, last);
                    c = read[0];
                    dataPos = read[1];
                    last = c;
                }

                out[outPos++] = a;
                out[outPos++] = b;
                out[outPos++] = c;

                vertexOffset = pushVertexFifo(vertexFifo, a, vertexOffset, 1);
                vertexOffset = pushVertexFifo(vertexFifo, b, vertexOffset, (feb == 0 || feb == 15) ? 1 : 0);
                vertexOffset = pushVertexFifo(vertexFifo, c, vertexOffset, (fec == 0 || fec == 15) ? 1 : 0);

                edgeOffset = pushEdgeFifo(edgeFifo, b, a, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, c, b, edgeOffset);
                edgeOffset = pushEdgeFifo(edgeFifo, a, c, edgeOffset);
            }
        }

        if (dataPos != dataSafeEnd) {
            throw new IllegalArgumentException("meshopt 索引流长度不符: 读完 " + dataPos + "，期望 " + dataSafeEnd);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // 内部工具
    // -----------------------------------------------------------------------

    private static int rotateTriangle(int a, int b, int c, int next) {
        return b == next ? 1 : (c == next ? 2 : 0);
    }

    private static int getEdgeFifo(int[] fifo, int a, int b, int c, int offset) {
        for (int i = 0; i < FIFO_SIZE; i++) {
            int index = ((offset - 1 - i) & 15) * 2;
            int e0 = fifo[index];
            int e1 = fifo[index + 1];
            if (e0 == a && e1 == b) {
                return (i << 2) | 0;
            }
            if (e0 == b && e1 == c) {
                return (i << 2) | 1;
            }
            if (e0 == c && e1 == a) {
                return (i << 2) | 2;
            }
        }
        return -1;
    }

    private static int pushEdgeFifo(int[] fifo, int a, int b, int offset) {
        fifo[offset * 2] = a;
        fifo[offset * 2 + 1] = b;
        return (offset + 1) & 15;
    }

    private static int getVertexFifo(int[] fifo, int v, int offset) {
        for (int i = 0; i < FIFO_SIZE; i++) {
            if (fifo[(offset - 1 - i) & 15] == v) {
                return i;
            }
        }
        return -1;
    }

    private static int pushVertexFifo(int[] fifo, int v, int offset, int advance) {
        fifo[offset] = v;
        return (offset + advance) & 15;
    }

    private static int indexOfCodeAux(int value) {
        for (int i = 0; i < 16; i++) {
            if (CODE_AUX_TABLE[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** 写一个自由索引：相对 {@code last} 的 zigzag 增量 + 变长整数。 */
    private static int writeIndex(byte[] out, int pos, int index, int last) {
        int delta = index - last;
        int value = (delta << 1) ^ (delta >> 31);
        return writeVByte(out, pos, value);
    }

    private static int[] readIndex(byte[] data, int pos, int last) {
        int[] read = readVByte(data, pos);
        int value = read[0];
        int delta = (value >>> 1) ^ -(value & 1);
        return new int[]{last + delta, read[1]};
    }

    private static int writeVByte(byte[] out, int pos, int value) {
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out[pos++] = (byte) v;
                return pos;
            }
            out[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    private static int[] readVByte(byte[] data, int pos) {
        int lead = data[pos++] & 0xFF;
        if (lead < 128) {
            return new int[]{lead, pos};
        }
        int result = lead & 0x7F;
        int shift = 7;
        for (int i = 0; i < 4; i++) {
            if (pos >= data.length) {
                throw new IllegalArgumentException("meshopt 索引流变长整数截断");
            }
            int group = data[pos++] & 0xFF;
            result |= (group & 0x7F) << shift;
            shift += 7;
            if (group < 128) {
                break;
            }
        }
        return new int[]{result, pos};
    }
}
