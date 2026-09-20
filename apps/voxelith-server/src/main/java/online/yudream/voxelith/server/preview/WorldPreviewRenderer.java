package online.yudream.voxelith.server.preview;

import online.yudream.voxelith.bake.domain.mesh.BiomeLookup;
import online.yudream.voxelith.bake.domain.mesh.BiomeTintResolver;
import online.yudream.voxelith.bake.domain.mesh.TintResolver;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.application.dto.ModelData;
import online.yudream.voxelith.resource.application.dto.ModelVariantData;
import online.yudream.voxelith.resource.application.dto.VariantGroup;
import online.yudream.voxelith.sharedkernel.color.ColorSpace;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.Identifier;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.tile.application.TextureColorSampler;
import online.yudream.voxelith.tile.infrastructure.image.CatalogTexturePixelSource;
import online.yudream.voxelith.world.domain.world.BlockStateSpec;
import online.yudream.voxelith.world.domain.world.ChunkData;
import online.yudream.voxelith.world.domain.world.ChunkSection;
import online.yudream.voxelith.world.domain.world.WorldReader;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离屏地表预览：把存档某个维度压成一张俯视色图（1 像素 = step 个方块）。
 *
 * <p>这是「上传地图 → 框选渲染范围」里的可视化底座：框选需要看得见地形，
 * 而完整 3D 渲染代价太高，所以走一条独立的轻量链路——只读区块 NBT、只取每根柱子
 * 最高的非空气方块，用与 bake 链路同一套规则（模型 up 面贴图平均色 × 群系染色）上色。</p>
 *
 * <p>为什么不是每方块 1 像素：大存档（数百个 region）逐方块扫描既慢又占内存，
 * 所以按 {@code step} 抽样，step 由目标边长自动推导（2 的幂，落在 [1,64]）。
 * 每个 region 独立并行处理，列与列之间映射到互不重叠的输出格，无需加锁。</p>
 */
public final class WorldPreviewRenderer {

    private static final Logger log = LoggerFactory.getLogger(WorldPreviewRenderer.class);

    /**
     * 预览结果：色图 + 地理信息（前端按 origin/step 把像素换算回方块坐标）。
     *
     * @param originX/originZ 左上角像素对应的方块坐标（= 有内容区块的包围盒左下角）
     * @param step            1 像素 = step 个方块
     * @param width/depth     像素宽 / 高
     * @param argb            行主序像素（行 0 = 最小 Z），无内容的格为 0（透明）
     * @param blockMinX..blockMaxZ 有内容区块的方块包围盒（含端点，已按区块对齐）
     * @param regions         存在内容的 region 坐标列表 [x, z]
     * @param chunkCount      非空区块数（按 region 头表统计，非精确到「有方块」）
     * @param minSurfaceY     抽样列里最低的地表高度；无数据时为 {@link #NO_SURFACE_Y}
     * @param maxSurfaceY     抽样列里最高的地表高度；无数据时为 {@link #NO_SURFACE_Y}
     */
    public record Preview(int originX, int originZ, int step, int width, int depth, int[] argb,
                          int blockMinX, int blockMaxX, int blockMinZ, int blockMaxZ,
                          List<int[]> regions, long chunkCount,
                          int minSurfaceY, int maxSurfaceY, List<Level> pyramid) {

        /**
         * 金字塔里的一级预览图（逐级 2 倍细化）。
         * level 0 = 最粗（整图缩略），最后一级 = 最细（原 step 网格）。
         */
        public record Level(int step, int width, int depth, int[] argb) {
        }

        /** 像素列 → 方块 X。 */
        public int blockXAt(int pixelX) {
            return originX + pixelX * step;
        }

        /** 像素行 → 方块 Z。 */
        public int blockZAt(int pixelZ) {
            return originZ + pixelZ * step;
        }
    }

    /** 没有取到任何地表高度时的哨兵。 */
    public static final int NO_SURFACE_Y = Integer.MIN_VALUE;

    /** 进度回调：已处理 region 数 / 总数。 */
    @FunctionalInterface
    public interface Progress {
        void onRegion(int done, int total);
    }

    /** 抽样步长只取 2 的幂：像素与区块（16 方块）对齐，网格线不会与方块边界错位。 */
    /** 无模型可解析时的兜底色（原版常见地表方块）。 */
    private static final Map<String, Integer> FALLBACK_COLORS = Map.ofEntries(
            Map.entry("minecraft:grass_block", 0x91BD59),
            Map.entry("minecraft:dirt", 0x8B6A47),
            Map.entry("minecraft:coarse_dirt", 0x7C5A3C),
            Map.entry("minecraft:podzol", 0x5C3D1E),
            Map.entry("minecraft:mycelium", 0x6F6265),
            Map.entry("minecraft:farmland", 0x6B4A2B),
            Map.entry("minecraft:grass_path", 0x948253),
            Map.entry("minecraft:dirt_path", 0x948253),
            Map.entry("minecraft:stone", 0x7A7A7A),
            Map.entry("minecraft:cobblestone", 0x7B7B7B),
            Map.entry("minecraft:smooth_stone", 0x9C9C9C),
            Map.entry("minecraft:andesite", 0x8A8A8A),
            Map.entry("minecraft:granite", 0x9A6B5A),
            Map.entry("minecraft:diorite", 0xC5C5C9),
            Map.entry("minecraft:deepslate", 0x505050),
            Map.entry("minecraft:gravel", 0x877F7C),
            Map.entry("minecraft:sand", 0xDBD3A0),
            Map.entry("minecraft:sandstone", 0xD8CD9A),
            Map.entry("minecraft:red_sand", 0xBE6821),
            Map.entry("minecraft:terracotta", 0x985E43),
            Map.entry("minecraft:water", 0x3F76E4),
            Map.entry("minecraft:lava", 0xE2560C),
            Map.entry("minecraft:ice", 0x9BD2F2),
            Map.entry("minecraft:snow_block", 0xF0FBFB),
            Map.entry("minecraft:snow", 0xF0FBFB),
            Map.entry("minecraft:oak_leaves", 0x4A7A28),
            Map.entry("minecraft:spruce_leaves", 0x619961),
            Map.entry("minecraft:birch_leaves", 0x80A755),
            Map.entry("minecraft:jungle_leaves", 0x30BB0B),
            Map.entry("minecraft:acacia_leaves", 0x6A7039),
            Map.entry("minecraft:dark_oak_leaves", 0x38761D),
            Map.entry("minecraft:oak_log", 0x6B5A32),
            Map.entry("minecraft:oak_planks", 0xB08B4E),
            Map.entry("minecraft:spruce_planks", 0x735A33),
            Map.entry("minecraft:bricks", 0x96584A),
            Map.entry("minecraft:stone_bricks", 0x7A7A7A),
            Map.entry("minecraft:glass", 0xC8E8F0),
            Map.entry("minecraft:white_concrete", 0xCFD5D6),
            Map.entry("minecraft:gray_concrete", 0x36393D),
            Map.entry("minecraft:light_gray_concrete", 0x7D7D73),
            Map.entry("minecraft:red_concrete", 0x8E2121),
            Map.entry("minecraft:bedrock", 0x565656));

    private static final int DEFAULT_COLOR = 0x7F8C8D;

    private final WorldReader reader;
    private final ResolvedResourceCatalog catalog;
    private final TextureColorSampler sampler;

    /**
     * 当前正在上色的柱子的群系。染色规则由 {@link BiomeTintResolver} 统一提供，
     * 而它按坐标取群系；预览已经手持区块截面、不想再走一次随机访问，于是把「当前柱子的群系」
     * 放在线程本地变量里让同一个染色器复用——每个柱子只在自己线程内完成取群系与染色，不会串。
     */
    private final ThreadLocal<String> columnBiome = ThreadLocal.withInitial(() -> null);
    private final TintResolver tint;

    /** 方块状态 → (up 面贴图线性平均色, tintIndex)；同一种状态全图只解析一次（跨 region 并行共享）。 */
    private final Map<String, OptionalInt> faceColorCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> tintIndexCache = new ConcurrentHashMap<>();

    public WorldPreviewRenderer(WorldReader reader, ResolvedResourceCatalog catalog) {
        this.reader = reader;
        this.catalog = catalog;
        // 没有可用资源包（未指定 client jar 且自动发现失败）时退化为内置色表：
        // 预览仍要能出图，只是没有真实贴图色与群系染色
        this.sampler = catalog == null ? null : new TextureColorSampler(new CatalogTexturePixelSource(catalog));
        this.tint = catalog == null
                ? (blockId, tintIndex, x, y, z) -> -1
                : new BiomeTintResolver(catalog,
                        (BiomeLookup) (x, y, z) -> Optional.ofNullable(columnBiome.get()));
    }

    /**
     * 维度目录：委托给 world 上下文的唯一实现，避免各链路各写一份 switch
     * （曾经地图画那条链路就漏掉了维度，下界/末地读不到展示框）。
     */
    public static Path dimensionDir(Path worldDir, String dimension) {
        return online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap
                .dimensionDir(worldDir, dimension);
    }

    /**
     * 渲染地表预览。
     *
     * @param worldDir 存档根目录
     * @param dimension 维度 id
     * @param maxSize 预览长边像素上限（step 按此推导）
     * @param threads 并行线程数（按 region 并行）
     * @param progress 进度回调，可为 null
     * @return 预览；该维度没有区块时返回 {@code null}
     */
    public Preview render(Path worldDir, String dimension, int maxSize, int threads,
                          Progress progress) {
        Path dimensionDir = dimensionDir(worldDir, dimension);
        Map<RegionPos, List<WorldReader.ChunkRef>> regions = reader.scanRegions(dimensionDir);
        if (regions.isEmpty()) {
            return null;
        }

        int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
        for (List<WorldReader.ChunkRef> refs : regions.values()) {
            for (WorldReader.ChunkRef ref : refs) {
                ChunkPos pos = ref.pos();
                minChunkX = Math.min(minChunkX, pos.x());
                maxChunkX = Math.max(maxChunkX, pos.x());
                minChunkZ = Math.min(minChunkZ, pos.z());
                maxChunkZ = Math.max(maxChunkZ, pos.z());
            }
        }
        if (minChunkX > maxChunkX) {
            return null;
        }

        int originX = minChunkX * 16;
        int originZ = minChunkZ * 16;
        int blockMaxX = (maxChunkX + 1) * 16 - 1;
        int blockMaxZ = (maxChunkZ + 1) * 16 - 1;
        int blocksWide = blockMaxX - originX + 1;
        int blocksDeep = blockMaxZ - originZ + 1;
        int step = pickStep(blocksWide, blocksDeep, Math.max(maxSize, 64));
        int width = (blocksWide + step - 1) / step;
        int depth = (blocksDeep + step - 1) / step;

        // 线性空间累加：sRGB 字节直接平均会把中间调整体提亮（与 AerialRaster 同一理由）
        int[] sumR = new int[width * depth];
        int[] sumG = new int[width * depth];
        int[] sumB = new int[width * depth];
        int[] samples = new int[width * depth];

        // 抽样列的地表高度范围：前端据此给出「最低渲染高度」的合理默认值。
        // 存档高度差异极大（本机实测有整片地形只在 y≈0~6 的存档），写死默认值会把几何整片裁掉。
        AtomicInteger minSurfaceY = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger maxSurfaceY = new AtomicInteger(Integer.MIN_VALUE);

        List<RegionPos> regionList = new ArrayList<>(regions.keySet());
        regionList.sort(Comparator.comparingInt(RegionPos::x).thenComparingInt(RegionPos::z));
        AtomicInteger done = new AtomicInteger();
        int total = regionList.size();
        AtomicInteger failed = new AtomicInteger();
        List<String> failureNotes = java.util.Collections.synchronizedList(new ArrayList<>());
        int poolSize = Math.max(1, Math.min(threads, total));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "voxelith-preview");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (RegionPos region : regionList) {
                futures.add(pool.submit(() -> {
                    try {
                        scanRegion(dimensionDir, region, originX, originZ, step, width, depth,
                                sumR, sumG, sumB, samples, minSurfaceY, maxSurfaceY);
                    } catch (Exception e) {
                        // 单个坏 region（0 字节文件、损坏区块、极端数据）不该毁掉整张预览：
                        // 跳过并记录，其余 region 照常出图。没有这层，一个坏文件就让
                        // 「框选范围」整步不可用。
                        failed.incrementAndGet();
                        failureNotes.add(region.x() + "," + region.z() + " → " + e);
                        log.warn("预览跳过 region {},{}: {}",
                                region.x(), region.z(), e.toString());
                    } finally {
                        if (progress != null) {
                            progress.onRegion(done.incrementAndGet(), total);
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("地表预览被中断", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("地表预览失败", e.getCause());
                }
            }
            if (failed.get() > 0) {
                log.warn("地表预览跳过了 {} 个无法解析的 region：{}", failed.get(),
                        String.join("; ", failureNotes));
            }
        } finally {
            pool.shutdownNow();
        }

        int[] argb = new int[width * depth];
        for (int i = 0; i < argb.length; i++) {
            if (samples[i] == 0) {
                continue;
            }
            int linear = (clampByte(sumR[i] / samples[i]) << 16)
                    | (clampByte(sumG[i] / samples[i]) << 8)
                    | clampByte(sumB[i] / samples[i]);
            argb[i] = 0xFF000000 | ColorSpace.linearToSrgbRgb(linear);
        }
        List<int[]> regionCoords = new ArrayList<>(total);
        long chunkCount = 0;
        for (RegionPos region : regionList) {
            regionCoords.add(new int[]{region.x(), region.z()});
            chunkCount += regions.get(region).size();
        }
        int lowest = minSurfaceY.get();
        int highest = maxSurfaceY.get();

        // 预览金字塔：从最细网格逐级 2× 框式降采样（透明格不参与平均），
        // 直到最长边 ≤ 512。前端可缩放画布按当前缩放取用对应层级。
        // 用独立局部变量（region 任务捕获的 width/depth/argb 必须保持 effectively final）。
        List<Preview.Level> pyramid = new ArrayList<>();
        pyramid.add(new Preview.Level(step, width, depth, argb));
        int pw = width;
        int pd = depth;
        int pstep = step;
        int[] prev = argb;
        while (Math.max(pw, pd) > 512 && pstep < (1 << 30)) {
            int nw = (pw + 1) / 2;
            int nd = (pd + 1) / 2;
            int[] next = new int[nw * nd];
            for (int y = 0; y < nd; y++) {
                for (int x = 0; x < nw; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int sy = Math.min(pd - 1, y * 2 + dy);
                        for (int dx = 0; dx < 2; dx++) {
                            int sx = Math.min(pw - 1, x * 2 + dx);
                            int c = prev[sy * pw + sx];
                            if ((c >>> 24) == 0) {
                                continue;
                            }
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    next[y * nw + x] = n == 0 ? 0
                            : 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                }
            }
            prev = next;
            pw = nw;
            pd = nd;
            pstep *= 2;
            pyramid.add(new Preview.Level(pstep, pw, pd, prev));
        }
        java.util.Collections.reverse(pyramid);   // 约定：index 0 = 最粗（全图缩略）
        return new Preview(originX, originZ, step, width, depth, argb,
                originX, blockMaxX, originZ, blockMaxZ, regionCoords, chunkCount,
                lowest == Integer.MAX_VALUE ? NO_SURFACE_Y : lowest,
                highest == Integer.MIN_VALUE ? NO_SURFACE_Y : highest,
                List.copyOf(pyramid));
    }

    /**
     * 抽样步长：2 的幂，取「长边 ÷ 目标像素」向上对齐到 2 的幂。
     *
     * <p><b>不设上限</b>：超大地图（洛圣都实测长边 2870 万方块）必须能缩到 maxSize 以内，
     * 否则输出像素网格（几十万像素宽）会让 ImageIO 直接拒绝。步长按 long 推导，
     * 免得大地图下的乘加溢出成 int 负数。</p>
     */
    private static int pickStep(long blocksWide, long blocksDeep, long maxSize) {
        long longest = Math.max(blocksWide, blocksDeep);
        long step = 1;
        while ((longest + step - 1) / step > maxSize) {
            step <<= 1;
        }
        return (int) Math.min(step, 1L << 30);
    }

    /**
     * 按需渲染一个方块矩形窗口（「可缩放框选」的取图入口）。
     *
     * <p>与 {@link #render} 的区别：render 是整张存档压成一张图（step 自动推导），
     * 本方法只扫与窗口相交的 region、按调用方给的 step 出图——缩放到哪就细到哪，
     * 结果由调用方缓存，同一窗口第二次直接命中。这就是 Xaero 式「按需细化」的后端部分。</p>
     *
     * @param minX/minZ 窗口左上角（最小 X/Z 方块坐标，含）
     * @param size      输出像素边长（正方形；窗口实际覆盖 size×step 方块）
     * @param step      每像素对应的方块数（1 = 逐方块细节）
     * @return 该维度没有可读数据时返回 {@code null}
     */
    public WindowPreview renderWindow(Path worldDir, String dimension,
                                      int minX, int minZ, int size, int step, int threads,
                                      Progress progress) {
        Path dimensionDir = dimensionDir(worldDir, dimension);
        int maxX = minX + size * step - 1;
        int maxZ = minZ + size * step - 1;
        int minRx = Math.floorDiv(minX, 512);
        int maxRx = Math.floorDiv(maxX, 512);
        int minRz = Math.floorDiv(minZ, 512);
        int maxRz = Math.floorDiv(maxZ, 512);

        int[] sumR = new int[size * size];
        int[] sumG = new int[size * size];
        int[] sumB = new int[size * size];
        int[] samples = new int[size * size];
        AtomicInteger minSurfaceY = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger maxSurfaceY = new AtomicInteger(Integer.MIN_VALUE);

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(threads, (maxRx - minRx + 1) * (maxRz - minRz + 1))),
                r -> {
                    Thread t = new Thread(r, "voxelith-preview-window");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int rx = minRx; rx <= maxRx; rx++) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    RegionPos region = new RegionPos(rx, rz);
                    final int frx = rx;
                    final int frz = rz;
                    futures.add(pool.submit(() -> {
                        try {
                            scanRegion(dimensionDir, region, minX, minZ, step, size, size,
                                    sumR, sumG, sumB, samples, minSurfaceY, maxSurfaceY);
                        } catch (Exception e) {
                            log.warn("窗口预览跳过 region {},{}: {}", frx, frz, e.toString());
                        } finally {
                            if (progress != null) {
                                progress.onRegion(0, 0);
                            }
                        }
                    }));
                }
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("窗口预览被中断", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("窗口预览失败", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        int[] argb = new int[size * size];
        for (int i = 0; i < argb.length; i++) {
            if (samples[i] == 0) {
                continue;
            }
            int linear = (clampByte(sumR[i] / samples[i]) << 16)
                    | (clampByte(sumG[i] / samples[i]) << 8)
                    | clampByte(sumB[i] / samples[i]);
            argb[i] = 0xFF000000 | ColorSpace.linearToSrgbRgb(linear);
        }
        return new WindowPreview(minX, minZ, step, size, argb, 0,
                minSurfaceY.get() == Integer.MAX_VALUE ? NO_SURFACE_Y : minSurfaceY.get(),
                maxSurfaceY.get() == Integer.MIN_VALUE ? NO_SURFACE_Y : maxSurfaceY.get());
    }

    /** 窗口预览结果：色图 + 覆盖范围。 */
    public record WindowPreview(int minX, int minZ, int step, int size, int[] argb,
                                long chunkCount, int minSurfaceY, int maxSurfaceY) {
    }


    private void scanRegion(Path dimensionDir, RegionPos region,
                            int originX, int originZ, int step, int width, int depth,
                            int[] sumR, int[] sumG, int[] sumB, int[] samples,
                            AtomicInteger minSurfaceY, AtomicInteger maxSurfaceY) {
        List<ChunkData> chunks = reader.readRegion(dimensionDir, region);
        for (ChunkData chunk : chunks) {
            int baseX = chunk.pos().x() * 16;
            int baseZ = chunk.pos().z() * 16;
            List<ChunkSection> sections = chunk.orderedSections();
            if (sections.isEmpty()) {
                continue;
            }
            // 采样列：worldX ≡ originX (mod step)。chunk 内第一个命中列由 baseX 的偏移决定。
            int firstX = Math.floorMod(originX - baseX, step);
            int firstZ = Math.floorMod(originZ - baseZ, step);
            for (int lx = firstX; lx < 16; lx += step) {
                int worldX = baseX + lx;
                int outX = (worldX - originX) / step;
                if (outX < 0 || outX >= width) {
                    continue;
                }
                for (int lz = firstZ; lz < 16; lz += step) {
                    int worldZ = baseZ + lz;
                    int outZ = (worldZ - originZ) / step;
                    if (outZ < 0 || outZ >= depth) {
                        continue;
                    }
                    Column column = topColumn(sections, lx, lz);
                    if (column == null) {
                        continue;
                    }
                    minSurfaceY.accumulateAndGet(column.worldY(), Math::min);
                    maxSurfaceY.accumulateAndGet(column.worldY(), Math::max);
                    columnBiome.set(column.biome());
                    int rgb;
                    try {
                        rgb = columnColor(column, worldX, worldZ);
                    } finally {
                        columnBiome.remove();
                    }
                    int index = outZ * width + outX;
                    sumR[index] += (rgb >> 16) & 0xFF;
                    sumG[index] += (rgb >> 8) & 0xFF;
                    sumB[index] += rgb & 0xFF;
                    samples[index]++;
                }
            }
        }
    }

    /** 柱子最高非空气方块。 */
    private record Column(BlockStateSpec state, int worldY, String biome) {
    }

    private Column topColumn(List<ChunkSection> sections, int localX, int localZ) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            ChunkSection section = sections.get(i);
            if (section.blockStates() == null || section.isAllAir()) {
                continue;
            }
            for (int localY = 15; localY >= 0; localY--) {
                BlockStateSpec spec = section.blockStates().get(localY * 256 + localZ * 16 + localX);
                if (isAir(spec.block().toString())) {
                    continue;
                }
                String biome = section.biomes() == null ? null
                        : section.biomes().get(((localY >> 2) * 16) + ((localZ >> 2) * 4) + (localX >> 2));
                return new Column(spec, section.y() * 16 + localY, biome);
            }
        }
        return null;
    }

    private static boolean isAir(String blockId) {
        return "minecraft:air".equals(blockId)
                || "minecraft:cave_air".equals(blockId)
                || "minecraft:void_air".equals(blockId);
    }

    /**
     * 方块 → 线性空间 RGB。贴图平均色 × 群系染色，与 bake 链路同规则；
     * 模型解析不出来时落回内置常见地表色表，保证预览总有东西可看。
     */
    private int columnColor(Column column, int worldX, int worldZ) {
        String blockId = column.state().block().toString();
        String key = column.state().toString();
        OptionalInt linear = faceColorCache.computeIfAbsent(key, k -> resolveFaceColor(column.state()));
        if (linear.isEmpty()) {
            int fallback = FALLBACK_COLORS.getOrDefault(blockId, DEFAULT_COLOR);
            return ColorSpace.srgbToLinearRgb(fallback);
        }
        int rgb = linear.getAsInt();
        int tintIndex = tintIndexCache.getOrDefault(key, -1);
        int tintRgb = tint.tint(blockId, tintIndex, worldX, column.worldY(), worldZ);
        if (tintRgb < 0) {
            return rgb;
        }
        int t = ColorSpace.srgbToLinearRgb(tintRgb);
        int r = ((rgb >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (t & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** 取模型朝上面（无 up 面则退到面积最大的面）的贴图平均色。 */
    private OptionalInt resolveFaceColor(BlockStateSpec state) {
        if (catalog == null) {
            return OptionalInt.empty();
        }
        List<VariantGroup> groups = catalog.selectVariantGroups(state.block(), state.properties());
        for (VariantGroup group : groups) {
            for (ModelVariantData alternative : group.alternatives()) {
                Optional<ModelData> resolved = catalog.model(Identifier.parse(alternative.model()));
                if (resolved.isEmpty()) {
                    continue;
                }
                ModelData model = resolved.get();
                Face face = bestFace(model);
                if (face == null) {
                    continue;
                }
                String textureId = resolveTexture(model, face.textureRef());
                if (textureId == null) {
                    continue;
                }
                int rgb = sampler.averageColorRgb(textureId);
                if (rgb >= 0) {
                    tintIndexCache.put(state.toString(), face.tintIndex());
                    return OptionalInt.of(rgb);
                }
            }
        }
        tintIndexCache.put(state.toString(), -1);
        return OptionalInt.empty();
    }

    private record Face(String textureRef, int tintIndex, float area) {
    }

    /** 优先 cullface=up 的面，其次任意面；取面积最大的那个（避免选中 0.1 格厚的装饰片）。 */
    private static Face bestFace(ModelData model) {
        Face up = null;
        Face any = null;
        for (ModelData.ElementData element : model.elements()) {
            float width = element.to()[0] - element.from()[0];
            float depth = element.to()[2] - element.from()[2];
            float height = element.to()[1] - element.from()[1];
            ModelData.FaceData upFace = element.faces().get("up");
            if (upFace != null) {
                Face candidate = new Face(upFace.texture(), upFace.tintIndex(), width * depth);
                if (up == null || candidate.area() > up.area()) {
                    up = candidate;
                }
            }
            for (Map.Entry<String, ModelData.FaceData> entry : element.faces().entrySet()) {
                ModelData.FaceData data = entry.getValue();
                float area = switch (entry.getKey()) {
                    case "up", "down" -> width * depth;
                    case "north", "south" -> width * height;
                    default -> depth * height;
                };
                Face candidate = new Face(data.texture(), data.tintIndex(), area);
                if (any == null || candidate.area() > any.area()) {
                    any = candidate;
                }
            }
        }
        return up != null ? up : any;
    }

    private static String resolveTexture(ModelData model, String textureRef) {
        if (textureRef == null) {
            return null;
        }
        if (!textureRef.startsWith("#")) {
            return textureRef;
        }
        return model.textures().get(textureRef.substring(1));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
