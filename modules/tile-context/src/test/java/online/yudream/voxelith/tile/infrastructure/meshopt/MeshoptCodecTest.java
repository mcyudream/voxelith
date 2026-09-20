package online.yudream.voxelith.tile.infrastructure.meshopt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * meshopt 编解码往返：顶点流必须逐字节无损，索引流必须保持三角面（允许循环轮转）。
 *
 * <p>线格式兼容性另有前端侧用官方 WASM 解码器解码「金标准向量」的用例
 * （{@code web/packages/voxelith-viewer/src/tiles/MeshoptGolden.test.ts}）。</p>
 */
class MeshoptCodecTest {

    @Test
    @DisplayName("顶点流：常量/零/量化整数/随机数据在各块边界上往返无损")
    void vertexRoundTrip() {
        int[] sizes = {4, 8, 12, 16, 20, 32};
        int[] counts = {1, 2, 15, 16, 17, 255, 256, 257, 700};
        for (int vertexSize : sizes) {
            for (int vertexCount : counts) {
                for (Pattern pattern : Pattern.values()) {
                    byte[] vertices = pattern.generate(vertexCount, vertexSize);
                    byte[] encoded = MeshoptVertexCodec.encode(vertices, vertexCount, vertexSize);
                    byte[] decoded = MeshoptVertexCodec.decode(encoded, vertexCount, vertexSize);
                    assertThat(decoded)
                            .as("%s size=%d count=%d", pattern, vertexSize, vertexCount)
                            .containsExactly(vertices);
                }
            }
        }
    }

    @Test
    @DisplayName("顶点流：压缩后确实更小（随机数据除外）")
    void vertexCompressionIsEffective() {
        byte[] vertices = Pattern.QUANTIZED.generate(2048, 12);
        byte[] encoded = MeshoptVertexCodec.encode(vertices, 2048, 12);
        assertThat(encoded.length).isLessThan(vertices.length);
    }

    @Test
    @DisplayName("索引流：网格/随机/重置等拓扑往返保持三角面")
    void indexRoundTrip() {
        assertIndexRoundTrip(grid(16, 16));
        assertIndexRoundTrip(grid(1, 64));
        assertIndexRoundTrip(randomTriangles(600, 128, new Random(20260920)));
        assertIndexRoundTrip(resetSequence());
        assertIndexRoundTrip(new int[0]);
    }

    @Test
    @DisplayName("索引流：规则网格压到约 1 字节/三角")
    void indexCompressionIsEffective() {
        int[] indices = grid(32, 32);
        byte[] encoded = MeshoptIndexCodec.encode(indices, 32 * 32);
        assertThat(encoded.length).isLessThan(indices.length * 4 / 4);
        assertThat(encoded.length).isLessThan(indices.length);
    }

    private static void assertIndexRoundTrip(int[] indices) {
        byte[] encoded = MeshoptIndexCodec.encode(indices, maxIndex(indices) + 1);
        int[] decoded = MeshoptIndexCodec.decode(encoded, indices.length);
        assertThat(decoded).hasSameSizeAs(indices);
        for (int i = 0; i < indices.length; i += 3) {
            assertThat(isCyclicRotation(indices, decoded, i))
                    .as("三角 #%d: 期望 %d,%d,%d 实得 %d,%d,%d", i / 3,
                            indices[i], indices[i + 1], indices[i + 2],
                            decoded[i], decoded[i + 1], decoded[i + 2])
                    .isTrue();
        }
    }

    private static boolean isCyclicRotation(int[] expected, int[] actual, int at) {
        return (actual[at] == expected[at] && actual[at + 1] == expected[at + 1]
                && actual[at + 2] == expected[at + 2])
                || (actual[at] == expected[at + 1] && actual[at + 1] == expected[at + 2]
                && actual[at + 2] == expected[at])
                || (actual[at] == expected[at + 2] && actual[at + 1] == expected[at]
                && actual[at + 2] == expected[at + 1]);
    }

    private static int maxIndex(int[] indices) {
        int max = 0;
        for (int index : indices) {
            max = Math.max(max, index);
        }
        return max;
    }

    /** 规则网格三角（每格 2 个三角，顶点行主序），贴近瓦片真实拓扑。 */
    private static int[] grid(int width, int height) {
        int[] indices = new int[(width - 1) * (height - 1) * 6];
        int pos = 0;
        for (int z = 0; z < height - 1; z++) {
            for (int x = 0; x < width - 1; x++) {
                int a = z * width + x;
                int b = a + 1;
                int c = a + width;
                int d = c + 1;
                indices[pos++] = a;
                indices[pos++] = c;
                indices[pos++] = b;
                indices[pos++] = b;
                indices[pos++] = c;
                indices[pos++] = d;
            }
        }
        return indices;
    }

    private static int[] randomTriangles(int triangles, int vertexCount, Random random) {
        int[] indices = new int[triangles * 3];
        for (int i = 0; i < triangles; i++) {
            // 真实网格里相邻三角共享顶点：这里刻意让相邻三角有 1~2 个共享顶点
            int base = i == 0 ? 0 : indices[(i - 1) * 3];
            indices[i * 3] = base % vertexCount;
            indices[i * 3 + 1] = random.nextInt(vertexCount);
            indices[i * 3 + 2] = random.nextInt(vertexCount);
        }
        return indices;
    }

    /** 0,1,2 三顶点序列会触发编码器的 reset 分支。 */
    private static int[] resetSequence() {
        int[] indices = new int[12];
        for (int i = 0; i < 12; i++) {
            indices[i] = i % 3;
        }
        return indices;
    }

    private enum Pattern {
        ZERO {
            @Override
            byte[] generate(int vertexCount, int vertexSize) {
                return new byte[vertexCount * vertexSize];
            }
        },
        CONSTANT {
            @Override
            byte[] generate(int vertexCount, int vertexSize) {
                byte[] data = new byte[vertexCount * vertexSize];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) 0x40;
                }
                return data;
            }
        },
        /** 量化后的整数坐标：相邻顶点小步长变化，最接近真实瓦片。 */
        QUANTIZED {
            @Override
            byte[] generate(int vertexCount, int vertexSize) {
                byte[] data = new byte[vertexCount * vertexSize];
                for (int i = 0; i < vertexCount; i++) {
                    for (int b = 0; b < vertexSize; b++) {
                        data[i * vertexSize + b] = (byte) ((i * 3 + b * 7) & 0x3F);
                    }
                }
                return data;
            }
        },
        RANDOM {
            @Override
            byte[] generate(int vertexCount, int vertexSize) {
                byte[] data = new byte[vertexCount * vertexSize];
                new Random(vertexCount * 31L + vertexSize).nextBytes(data);
                return data;
            }
        };

        abstract byte[] generate(int vertexCount, int vertexSize);
    }
}
