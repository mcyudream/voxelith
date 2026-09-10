package online.yudream.voxelith.bake.domain.mesh;

import online.yudream.voxelith.bake.domain.geometry.Quad;
import online.yudream.voxelith.sharedkernel.vo.Direction;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.application.dto.BlockStateData;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * 逐顶点光照 + AO 采样器（BlueMap/原版式角点算法）。
 *
 * 为减少随机访问开销，按区块截面预取 18³ 邻域网格（截面 ±1 格边界）：
 * 不透明度（满方块遮挡）、天空光、方块光。采样时直接读网格，无需再访问世界。
 *
 * AO：对每个顶点取其所在角点的 3 个邻接格（side1、side2、corner），
 * 遮挡级数 n = 遮挡格数（两侧同时遮挡时按原版规则 n = 3），亮度系数 = 1 - 0.25n。
 * 光照：角点 4 格（base + side1 + side2 + corner）中非遮挡格的光照平均。
 */
public final class VertexLightSampler {

    /** 网格边长：16 + 两侧各 1 格边界。 */
    private static final int EXT = 18;
    private static final int VOLUME = EXT * EXT * EXT;

    private final WorldBlockAccess world;
    private final Predicate<BlockStateData> opaqueTester;

    /**
     * @param opaqueTester 判断方块状态是否为"满不透明方块"（参与 AO 遮挡），由调用方提供（带缓存）
     */
    public VertexLightSampler(WorldBlockAccess world, Predicate<BlockStateData> opaqueTester) {
        this.world = world;
        this.opaqueTester = opaqueTester;
    }

    /** 预取截面 (baseX, baseY, baseZ) 的 18³ 邻域网格。 */
    public SectionGrid buildGrid(int baseX, int baseY, int baseZ) {
        byte[] solid = new byte[VOLUME];
        byte[] sky = new byte[VOLUME];
        byte[] block = new byte[VOLUME];
        for (int gy = -1; gy <= 16; gy++) {
            for (int gz = -1; gz <= 16; gz++) {
                for (int gx = -1; gx <= 16; gx++) {
                    int x = baseX + gx;
                    int y = baseY + gy;
                    int z = baseZ + gz;
                    int idx = index(gx, gy, gz);
                    Optional<BlockStateData> state = world.blockStateAt(x, y, z);
                    if (state.isPresent() && !state.get().isAir() && opaqueTester.test(state.get())) {
                        solid[idx] = 1;
                    }
                    sky[idx] = (byte) world.skyLightAt(x, y, z);
                    block[idx] = (byte) world.blockLightAt(x, y, z);
                }
            }
        }
        return new SectionGrid(baseX, baseY, baseZ, solid, sky, block);
    }

    /**
     * 采样一个 quad（模型局部坐标，未经 toWorld）在方块 (x,y,z) 处的逐顶点光照与 AO。
     *
     * @param skyOut   输出：4 顶点天空光 0..15
     * @param blockOut 输出：4 顶点方块光 0..15
     * @param aoOut    输出：4 顶点 AO 遮挡级数 0..3
     */
    public void sample(Quad quad, int x, int y, int z, SectionGrid grid,
                       byte[] skyOut, byte[] blockOut, byte[] aoOut) {
        Direction dir = dominantDirection(quad.normal());
        int bx = x + dir.nx();
        int by = y + dir.ny();
        int bz = z + dir.nz();

        // 两个切向轴（与面法向垂直）
        int t1 = dir.nx() != 0 ? 1 : 0;   // 面法向为 x 时，切向取 y、z；否则取 x 与另一轴
        int t2 = dir.nx() != 0 ? 2 : (dir.ny() != 0 ? 2 : 1);

        for (int v = 0; v < Quad.VERTEX_COUNT; v++) {
            float lx = quad.positions()[v * 3] / 16f;
            float ly = quad.positions()[v * 3 + 1] / 16f;
            float lz = quad.positions()[v * 3 + 2] / 16f;
            int s1 = sign(axis(lx, ly, lz, t1));
            int s2 = sign(axis(lx, ly, lz, t2));

            int[] side1 = offset(bx, by, bz, t1, s1);
            int[] side2 = offset(bx, by, bz, t2, s2);
            int[] corner = offset(side1[0], side1[1], side1[2], t2, s2);

            boolean o1 = grid.solidAt(side1[0], side1[1], side1[2]);
            boolean o2 = grid.solidAt(side2[0], side2[1], side2[2]);
            boolean oc = grid.solidAt(corner[0], corner[1], corner[2]);
            int n = (o1 && o2) ? 3 : (o1 ? 1 : 0) + (o2 ? 1 : 0) + (oc ? 1 : 0);
            aoOut[v] = (byte) n;

            // 光照：base + side1 + side2 + corner 中非遮挡格平均（全遮挡时用 base，正常不会发生）
            int skySum = 0;
            int blockSum = 0;
            int count = 0;
            if (!grid.solidAt(bx, by, bz)) {
                skySum += grid.skyAt(bx, by, bz);
                blockSum += grid.blockAt(bx, by, bz);
                count++;
            }
            if (!o1) {
                skySum += grid.skyAt(side1[0], side1[1], side1[2]);
                blockSum += grid.blockAt(side1[0], side1[1], side1[2]);
                count++;
            }
            if (!o2) {
                skySum += grid.skyAt(side2[0], side2[1], side2[2]);
                blockSum += grid.blockAt(side2[0], side2[1], side2[2]);
                count++;
            }
            if (!oc) {
                skySum += grid.skyAt(corner[0], corner[1], corner[2]);
                blockSum += grid.blockAt(corner[0], corner[1], corner[2]);
                count++;
            }
            if (count == 0) {
                skyOut[v] = (byte) grid.skyAt(bx, by, bz);
                blockOut[v] = (byte) grid.blockAt(bx, by, bz);
            } else {
                skyOut[v] = (byte) (skySum / count);
                blockOut[v] = (byte) (blockSum / count);
            }
        }
    }

    /** 单位法向 → 最近轴向方向（对角斜面取最近轴，AO 近似可接受）。 */
    private static Direction dominantDirection(float[] normal) {
        float ax = Math.abs(normal[0]);
        float ay = Math.abs(normal[1]);
        float az = Math.abs(normal[2]);
        if (ax >= ay && ax >= az) {
            return normal[0] >= 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return normal[1] >= 0 ? Direction.UP : Direction.DOWN;
        }
        return normal[2] >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static float axis(float x, float y, float z, int axis) {
        return axis == 0 ? x : (axis == 1 ? y : z);
    }

    private static int sign(float local) {
        return local > 0.5f ? 1 : -1;
    }

    private static int[] offset(int x, int y, int z, int axis, int delta) {
        return switch (axis) {
            case 0 -> new int[]{x + delta, y, z};
            case 1 -> new int[]{x, y + delta, z};
            default -> new int[]{x, y, z + delta};
        };
    }

    private static int index(int gx, int gy, int gz) {
        return ((gy + 1) * EXT + (gz + 1)) * EXT + (gx + 1);
    }

    /** 截面 18³ 邻域网格（世界坐标经 base 偏移索引，越界格视为空气/无光）。 */
    public record SectionGrid(int baseX, int baseY, int baseZ,
                              byte[] solid, byte[] sky, byte[] block) {

        public boolean solidAt(int x, int y, int z) {
            int idx = localIndex(x, y, z);
            return idx >= 0 && solid[idx] == 1;
        }

        public int skyAt(int x, int y, int z) {
            int idx = localIndex(x, y, z);
            return idx < 0 ? 0 : sky[idx];
        }

        public int blockAt(int x, int y, int z) {
            int idx = localIndex(x, y, z);
            return idx < 0 ? 0 : block[idx];
        }

        private int localIndex(int x, int y, int z) {
            int gx = x - baseX;
            int gy = y - baseY;
            int gz = z - baseZ;
            if (gx < -1 || gx > 16 || gy < -1 || gy > 16 || gz < -1 || gz > 16) {
                return -1;
            }
            return index(gx, gy, gz);
        }
    }
}
