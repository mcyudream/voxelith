package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.lod.application.HeightfieldStore;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;
import online.yudream.voxelith.lod.infrastructure.heightfield.FileHeightfieldStore;
import online.yudream.voxelith.tile.infrastructure.bootstrap.TileContextBootstrap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后端全景渲染：把整张地图烘成一张透视全景图（服务端预渲染）。
 *
 * <p>动机：客户端要"全景不虚化"就得同时画出全部 hires 几何（本校实测 ≈2200 万三角形），
 * 弱机做不到。这类工作适合放到服务端一次烘好，客户端只加载一张 PNG —— 这正是
 * "把负载放在后端"的落点。</p>
 *
 * <p>输入是渲染管线已经产出的两份中间产物（不重跑管线）：</p>
 * <ul>
 *   <li>{@code <workDir>/surface.png} + {@code surface.json}：逐格地表色，1 像素 = 1 方块；</li>
 *   <li>{@code <workDir>/heightfield.bin}：逐柱地表高度（列宽 = footprint 方块）。</li>
 * </ul>
 *
 * <p>算法：高度场光线步进。每个像素从相机发一条射线，沿射线按方块步进，
 * 命中高度场后取该格地表色，乘以简单山体光照 + 距离雾。纯 CPU、不依赖 GL，
 * 200 万像素约数秒。输出 {@code <publishDir>/<mapId>/panorama.png} 与
 * {@code panorama.json}（相机参数，供前端摆放），另复制一张正交航拍图 {@code aerial.png}。</p>
 *
 * <p>注意：本类只读渲染产物、只写全景文件，<b>不修改任何 3D 瓦片链路</b>——3D 效果保持不变。</p>
 */
public final class PanoramaCli {

    private static final int SKY = 0xFF87CEEB;

    /** 光照方向（从西北上方打光，单位向量）。 */
    private static final double[] LIGHT = normalize(new double[]{-0.45, 0.8, -0.4});

    public static void main(String[] args) {
        String workDir = "./work";
        String publishDir = "./data/maps";
        String mapId = null;
        int width = 3840;
        int height = 2160;
        double yaw = 0;          // 0 = 朝北（-Z）
        double pitch = 45;       // 俯角（度）
        double fov = 60;
        double distanceFactor = 1.25;  // 相机距离 = 地图对角线 × 该系数
        String outputName = "panorama.png";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--work-dir" -> workDir = args[++i];
                case "--publish-dir" -> publishDir = args[++i];
                case "--map-id" -> mapId = args[++i];
                case "--width" -> width = Integer.parseInt(args[++i]);
                case "--height" -> height = Integer.parseInt(args[++i]);
                case "--yaw" -> yaw = Double.parseDouble(args[++i]);
                case "--pitch" -> pitch = Double.parseDouble(args[++i]);
                case "--fov" -> fov = Double.parseDouble(args[++i]);
                case "--distance" -> distanceFactor = Double.parseDouble(args[++i]);
                case "--output" -> outputName = args[++i];
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (mapId == null) {
            System.err.println("缺少 --map-id");
            System.exit(2);
        }
        System.exit(run(Path.of(workDir), Path.of(publishDir), mapId,
                width, height, yaw, pitch, fov, distanceFactor, outputName));
    }

    /** 渲染并写出全景图，返回进程退出码。 */
    public static int run(Path workDir, Path publishDir, String mapId,
                          int width, int height, double yaw, double pitch,
                          double fov, double distanceFactor, String outputName) {
        Surface surface = loadSurface(workDir).orElse(null);
        if (surface == null) {
            System.err.println("找不到 " + workDir.resolve("surface.png")
                    + "（先跑一次 renderMap 生成地表栅格）");
            return 1;
        }
        HeightfieldStore store = new FileHeightfieldStore(workDir.resolve("heightfield.bin"));
        Optional<Heightfield> loaded = store.load();
        if (loaded.isEmpty()) {
            System.err.println("找不到 " + workDir.resolve("heightfield.bin") + "（先跑一次 renderMap）");
            return 1;
        }
        Heightfield field = loaded.get();

        long t0 = System.currentTimeMillis();
        int[] argb = render(surface, field, width, height, yaw, pitch, fov, distanceFactor);
        Path mapDir = publishDir.resolve(mapId);
        try {
            Files.createDirectories(mapDir);
            byte[] png = TileContextBootstrap.openImageCodec().encodePng(width, height, argb);
            Files.write(mapDir.resolve(outputName), png);
            Files.copy(workDir.resolve("surface.png"), mapDir.resolve("aerial.png"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(mapDir.resolve("panorama.json"), """
                    {"file":"%s","width":%d,"height":%d,"yaw":%s,"pitch":%s,"fov":%s,\
                    "aerial":{"file":"aerial.png","originX":%d,"originZ":%d,"width":%d,"depth":%d,"pixelsPerBlock":1}}
                    """.formatted(outputName, width, height, yaw, pitch, fov,
                    surface.originX, surface.originZ, surface.width, surface.depth),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写全景图失败", e);
        }
        System.out.printf("全景已输出：%s（%d×%d，%.1fs）%s",
                mapDir.resolve(outputName), width, height,
                (System.currentTimeMillis() - t0) / 1000.0, System.lineSeparator());
        return 0;
    }

    /** 逐格地表色图（1 像素 = 1 方块）。 */
    private record Surface(int originX, int originZ, int width, int depth, int[] argb) {
    }

    private static Optional<Surface> loadSurface(Path workDir) {
        Path png = workDir.resolve("surface.png");
        Path meta = workDir.resolve("surface.json");
        if (!Files.isRegularFile(png) || !Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(png.toFile());
            String json = Files.readString(meta, StandardCharsets.UTF_8);
            int originX = intField(json, "originX");
            int originZ = intField(json, "originZ");
            int width = intField(json, "width");
            int depth = intField(json, "depth");
            int[] argb = new int[width * depth];
            for (int z = 0; z < depth && z < image.getHeight(); z++) {
                for (int x = 0; x < width && x < image.getWidth(); x++) {
                    argb[z * width + x] = image.getRGB(x, z);
                }
            }
            return Optional.of(new Surface(originX, originZ, width, depth, argb));
        } catch (IOException e) {
            throw new UncheckedIOException("读取地表栅格失败", e);
        }
    }

    private static int intField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("surface.json 缺少字段: " + key);
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * 高度场光线步进。相机沿 {@code viewDir} 回退到地图对角线的 distanceFactor 倍处，
     * 逐像素发射射线；命中后取该格地表色做山体光照与距离雾。
     */
    static int[] render(Surface surface, Heightfield field,
                        int width, int height, double yawDeg, double pitchDeg,
                        double fovDeg, double distanceFactor) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        // viewDir：yaw 0 = 朝北(-Z)，pitch 越大越俯视
        double[] viewDir = normalize(new double[]{
                Math.cos(pitch) * Math.sin(yaw),
                -Math.sin(pitch),
                -Math.cos(pitch) * Math.cos(yaw)});

        double centerX = surface.originX + surface.width / 2.0;
        double centerZ = surface.originZ + surface.depth / 2.0;
        double maxTop = maxTopY(field);
        double centerY = field.floorY() + Math.max(1, maxTop - field.floorY()) * 0.35;
        double diagonal = Math.hypot(surface.width, surface.depth);
        double dist = diagonal * distanceFactor;
        double[] eye = {
                centerX - viewDir[0] * dist,
                centerY - viewDir[1] * dist,
                centerZ - viewDir[2] * dist};

        double[] right = normalize(cross(viewDir, new double[]{0, 1, 0}));
        double[] up = cross(right, viewDir);
        double focal = 1.0 / Math.tan(Math.toRadians(fovDeg) / 2);
        double aspect = (double) width / height;
        double maxT = dist * 3.0 + diagonal;
        double step = 1.5;   // 步长（方块）：够精细，也不至于太慢

        int[] out = new int[width * height];
        for (int py = 0; py < height; py++) {
            double sy = (1 - 2.0 * (py + 0.5) / height);
            for (int px = 0; px < width; px++) {
                double sx = (2.0 * (px + 0.5) / width - 1) * aspect;
                double[] dir = normalize(new double[]{
                        viewDir[0] * focal + right[0] * sx + up[0] * sy,
                        viewDir[1] * focal + right[1] * sx + up[1] * sy,
                        viewDir[2] * focal + right[2] * sx + up[2] * sy});
                out[py * width + px] = march(surface, field, eye, dir, maxT, step, dist);
            }
        }
        return out;
    }

    /** 单条射线：返回命中颜色，未命中返回天空色。 */
    private static int march(Surface surface, Heightfield field, double[] eye, double[] dir,
                             double maxT, double step, double eyeDistance) {
        double t = 0;
        while (t < maxT) {
            t += step;
            double x = eye[0] + dir[0] * t;
            double y = eye[1] + dir[1] * t;
            double z = eye[2] + dir[2] * t;
            if (y < field.floorY() - 8) {
                // 已经低于地形底面：不可能再命中（相机在高处往下看）
                if (dir[1] <= 0) {
                    return SKY;
                }
                continue;
            }
            float h = heightAt(field, x, z);
            if (Float.isNaN(h)) {
                continue;
            }
            if (y <= h) {
                int color = colorAt(surface, x, z);
                if (color == 0) {
                    continue;
                }
                return shade(color, slope(field, x, z), t, eyeDistance);
            }
        }
        return SKY;
    }

    /** 高度场里的最高地表（无有效列时退回 floorY）。 */
    private static double maxTopY(Heightfield field) {
        float[] tops = field.copyTopY();
        float max = Float.NEGATIVE_INFINITY;
        for (float y : tops) {
            if (Float.isFinite(y) && y > max) {
                max = y;
            }
        }
        return Float.isFinite(max) ? max : field.floorY();
    }

    /**
     * 该点地表高度；不在高度场范围内返回 NaN。
     *
     * <p>坐标口径：{@code Heightfield.originX()/width()} 与 {@code topY(cx,cz)} 用的都是
     * <b>全局柱坐标</b>（1 柱 = footprint 方块），不是方块坐标——按方块算成局部柱号会全部落空
     * （实测表现为整张全景全是天空）。</p>
     */
    private static float heightAt(Heightfield field, double x, double z) {
        int cx = (int) Math.floor(x / (double) field.footprint());
        int cz = (int) Math.floor(z / (double) field.footprint());
        return field.topY(cx, cz);
    }

    /** 该点的坡面法线（用于山体光照）；用有限差分估计。 */
    private static double[] slope(Heightfield field, double x, double z) {
        double d = field.footprint();
        float hx1 = heightAt(field, x + d, z);
        float hx0 = heightAt(field, x - d, z);
        float hz1 = heightAt(field, x, z + d);
        float hz0 = heightAt(field, x, z - d);
        double dx = Float.isNaN(hx1) || Float.isNaN(hx0) ? 0 : (hx1 - hx0) / (2 * d);
        double dz = Float.isNaN(hz1) || Float.isNaN(hz0) ? 0 : (hz1 - hz0) / (2 * d);
        return normalize(new double[]{-dx, 1, -dz});
    }

    /** 该点的地表色（1 像素 = 1 方块）；无表面返回 0。 */
    private static int colorAt(Surface surface, double x, double z) {
        int ix = (int) Math.floor(x) - surface.originX;
        int iz = (int) Math.floor(z) - surface.originZ;
        if (ix < 0 || iz < 0 || ix >= surface.width || iz >= surface.depth) {
            return 0;
        }
        return surface.argb[iz * surface.width + ix];
    }

    /** 山体光照 + 距离雾。 */
    private static int shade(int argb, double[] normal, double t, double eyeDistance) {
        double lambert = Math.max(0.25, normal[0] * LIGHT[0] + normal[1] * LIGHT[1] + normal[2] * LIGHT[2]);
        // 距离雾：越远越淡入天空色，边缘不会出现生硬切面
        double fog = Math.min(0.65, Math.pow(Math.max(0, t - eyeDistance) / eyeDistance, 1.6) * 0.65);
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int sr = (SKY >> 16) & 0xFF;
        int sg = (SKY >> 8) & 0xFF;
        int sb = SKY & 0xFF;
        int fr = (int) Math.round(Math.min(255, r * lambert) * (1 - fog) + sr * fog);
        int fg = (int) Math.round(Math.min(255, g * lambert) * (1 - fog) + sg * fog);
        int fb = (int) Math.round(Math.min(255, b * lambert) * (1 - fog) + sb * fog);
        return 0xFF000000 | (fr << 16) | (fg << 8) | fb;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len == 0) {
            return new double[]{0, 0, 0};
        }
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private PanoramaCli() {
    }
}
