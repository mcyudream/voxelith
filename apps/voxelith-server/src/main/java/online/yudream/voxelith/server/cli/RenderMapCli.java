package online.yudream.voxelith.server.cli;

import online.yudream.voxelith.bake.application.BakeChunksUseCase;
import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.bake.application.BakeOutcome;
import online.yudream.voxelith.bake.application.BakedMeshMapper;
import online.yudream.voxelith.bake.application.dto.BakedChunkMeshData;
import online.yudream.voxelith.bake.domain.geometry.PrebakedQuadSource;
import online.yudream.voxelith.bake.infrastructure.artifact.FileBakeArtifactSink;
import online.yudream.voxelith.bake.infrastructure.prebaked.NdjsonPrebakedQuadSource;
import online.yudream.voxelith.lod.application.GenerateLodPyramidUseCase;
import online.yudream.voxelith.lod.application.HeightfieldStore;
import online.yudream.voxelith.lod.application.LodCommand;
import online.yudream.voxelith.lod.application.LodOutcome;
import online.yudream.voxelith.lod.domain.heightfield.AerialRaster;
import online.yudream.voxelith.lod.infrastructure.heightfield.FileHeightfieldStore;
import online.yudream.voxelith.resource.application.ResolvedResourceCatalog;
import online.yudream.voxelith.resource.infrastructure.bootstrap.ResourceContextBootstrap;
import online.yudream.voxelith.sharedkernel.vo.ChunkPos;
import online.yudream.voxelith.sharedkernel.vo.RegionPos;
import online.yudream.voxelith.tile.application.GenerateTilesUseCase;
import online.yudream.voxelith.tile.application.AtlasReuse;
import online.yudream.voxelith.tile.application.TileCommand;
import online.yudream.voxelith.tile.application.TileOutcome;
import online.yudream.voxelith.tile.domain.manifest.MapManifest;
import online.yudream.voxelith.tile.domain.tile.EncodeOptions;
import online.yudream.voxelith.tile.infrastructure.bootstrap.TileContextBootstrap;
import online.yudream.voxelith.tile.infrastructure.image.CatalogTexturePixelSource;
import online.yudream.voxelith.world.application.WorldBlockAccess;
import online.yudream.voxelith.world.domain.world.MapArtFrame;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilMapArtReader;
import online.yudream.voxelith.world.infrastructure.bootstrap.WorldContextBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仓库内全量管线入口（路线图 Phase 7 的「仓库内管线入口」）：把存档的指定 region 窗口渲染成
 * 可发布地图，串起 bake → tile → lod → manifest 四段。
 *
 * <p>此前这几段只在 jshell 里手工串联、仓库内没有入口。本入口把它们收进仓库，并且：</p>
 * <ul>
 *   <li>hires 图集 + LOD 分层图集页都由管线自己产出（图集布局按本次 run 用到的贴图打包，
 *       所以必须**单遍**跑完一个窗口——分多遍每遍的布局不同，跨遍的瓦片 UV 会对不上）；</li>
 *   <li>结束时把 tiles/ + atlas.png + atlas-layout.json + heightfield.bin + manifest.json
 *       一并发布到 {@code {publishDir}/{mapId}}，服务端直接可服务。</li>
 * </ul>
 *
 * <p>退出码：0 = 已发布，1 = 渲染失败，2 = 参数错误，3 = 范围超过单次渲染上限，
 * 4 = 内存不足。3 / 4 分开是因为它们给用户的下一步动作不同（缩范围 vs 缩范围或加堆）。</p>
 */
public final class RenderMapCli {

    private static final Logger log = LoggerFactory.getLogger(RenderMapCli.class);

    /** region 文件名 r.<x>.<z>.mca。 */
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public static void main(String[] args) {
        RenderMapOptions options;
        try {
            options = RenderMapOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println();
            System.err.println(RenderMapOptions.USAGE);
            System.exit(2);
            return;
        }
        System.exit(run(options));
    }

    /** 执行渲染并发布，返回进程退出码。便于测试直接调用而不退出 JVM。 */
    public static int run(RenderMapOptions options) {
        return run(options, System.out, System.err);
    }

    /**
     * 可注入输出流的重载：服务端进程内触发渲染时把日志接到任务日志，
     * 而不是直接打到服务进程的 stdout（CLI 行为不变，仍走 {@link #run(RenderMapOptions)}）。
     */
    public static int run(RenderMapOptions options, PrintStream out, PrintStream err) {
        Path regionDir = regionDir(options);
        if (!Files.isDirectory(regionDir)) {
            err.println("找不到 region 目录: " + regionDir);
            return 1;
        }

        List<int[]> regionWindow = regionWindow(options, regionDir);
        if (regionWindow.isEmpty()) {
            err.println("框选范围内没有 region 文件（存档目录: " + regionDir + "）："
                    + "确认范围落在有内容的区域上（或者去掉方块范围，改为整张图渲染）");
            return 1;
        }

        out.printf("资源包（低→高优先级）:%n");
        options.packs().forEach(p -> out.println("  " + p));
        ResolvedResourceCatalog catalog = ResourceContextBootstrap.openCatalog(options.packs());

        long t0 = System.currentTimeMillis();
        try (WorldBlockAccess world = WorldContextBootstrap.openBlockAccess(
                options.worldDir(), options.dimension())) {

            List<ChunkPos> chunks = collectChunks(world, regionWindow, options, out);
            out.printf("%n维度 %s：窗口 %d 个 region，非空区块 %d%n",
                    options.dimension(), regionWindow.size(), chunks.size());
            if (chunks.isEmpty()) {
                err.println("窗口内没有非空区块");
                return 1;
            }
            if (options.maxChunks() > 0 && chunks.size() > options.maxChunks()) {
                err.printf("范围过大：窗口内非空区块 %d 个，超过单次渲染上限 %d 个。%n",
                        chunks.size(), options.maxChunks());
                err.println("  原因：bake 会把窗口内所有区块的网格一次性留在内存里，"
                        + "范围越大越容易在中途内存不足而失败。");
                err.printf("  建议：把框选范围缩到目标的 1/%d 左右（例如只框校园 / 城区），"
                        + "或调大服务端配置 yudream.voxelith.render.max-chunks。%n",
                        Math.max(2, chunks.size() / Math.max(1, options.maxChunks()) + 1));
                return 3;
            }

            // 模型几何：默认先用 headless 采集拿游戏自己烘焙的 quad（uvlock / 元素旋转等按定义正确），
            // 缓存到 <workDir>/models.json.gz；静态解析只在采集不可用或被显式跳过时兜底。
            Path models = ensureModels(options, out);
            PrebakedQuadSource prebaked = models == null ? null : NdjsonPrebakedQuadSource.load(models);

            // 窗口大到单遍放不下时切多遍：峰值内存只与单批相关，窗口想多大都行。
            // 没有采集产物时不分遍——共享图集预打不出贴图全表，硬分会整片品红
            if (options.batchChunks() > 0 && chunks.size() > options.batchChunks() && prebaked != null) {
                return renderInBatches(options, world, catalog, chunks, prebaked, out, err);
            }

            BakeChunksUseCase bake = new BakeChunksUseCase(
                    catalog, world, new FileBakeArtifactSink(), prebaked);
            long tBake = System.currentTimeMillis();
            BakeOutcome baked = bake.bake(new BakeCommand(
                    chunks, options.workDir(), options.sampleChunks(), options.minY()));
            out.printf("%nbake 完成：区块 %d，方块 %d，quad %d，耗时 %.1fs%n",
                    baked.chunksBaked(), baked.blocksBaked(), baked.quadsBaked(),
                    (System.currentTimeMillis() - tBake) / 1000.0);
            if (options.minY() != BakeCommand.NO_MIN_Y) {
                out.printf("  已裁掉 Y <%d 的地下几何%n", options.minY());
            }
            reportMissing(baked.missing(), out);

            // domain 网格 → application 契约 DTO，供 tile/lod 消费（同一份内存产物）
            Map<ChunkPos, BakedChunkMeshData> meshes = BakedMeshMapper.toData(baked.meshes());

            // 地图画：展示框是实体、地图颜色在 data/map_*.dat，方块链路完全看不到，
            // 所以在 tile 之前把它们读出来补成面片 + 把地图注册成图集贴图
            MapArtInjector mapArt = new MapArtInjector(new CatalogTexturePixelSource(catalog));
            MeshesAndMapArt prepared = injectMapArt(options, regionWindow, meshes, mapArt, out);
            meshes = prepared.meshes();

            GenerateTilesUseCase tiles = TileContextBootstrap.openGenerator(catalog, mapArt);
            long tTile = System.currentTimeMillis();
            // 共享图集：瓦片只带 UV，不再每片内嵌 1.7MB 图集（前端挂清单 atlas 那张共享纹理）
            TileOutcome hires = tiles.generate(new TileCommand(meshes, options.workDir(),
                    EncodeOptions.sharedAtlas()));
            out.printf("tile 完成：hires 瓦片 %d，图集 %d² / %d 格，耗时 %.1fs%n",
                    hires.tiles().size(), hires.atlasSize(), hires.textureCount(),
                    (System.currentTimeMillis() - tTile) / 1000.0);
            reportMissingTextures(hires, out);

            GenerateLodPyramidUseCase lod = new GenerateLodPyramidUseCase(
                    TileContextBootstrap.openTextureColorSampler(catalog),
                    TileContextBootstrap.openVertexColorTileExporter(),
                    lodImageCodec(options));
            HeightfieldStore store = new FileHeightfieldStore(
                    options.workDir().resolve("heightfield.bin"));
            long tLod = System.currentTimeMillis();
            LodOutcome pyramid = lod.generate(new LodCommand(
                    meshes, options.workDir(), options.maxLevel(), store, List.of()));
            out.printf("lod 完成：LOD 瓦片 %d，层级 %d，耗时 %.1fs%n",
                    pyramid.tiles().size(), pyramid.levels(),
                    (System.currentTimeMillis() - tLod) / 1000.0);
            for (var page : pyramid.atlasPages()) {
                out.printf("  图集页 L%d slot=%d %s%n", page.level(), page.slotSize(), page.url());
            }
            dumpSurface(options, pyramid, lodImageCodec(options), out);

            List<TileOutcome.TileSummary> all = new ArrayList<>(hires.tiles());
            all.addAll(pyramid.tiles());
            TileOutcome merged = new TileOutcome(
                    all, hires.atlasFile(), hires.reportFile(),
                    hires.textureCount(), hires.atlasSize(),
                    hires.missingTextures(), hires.untexturedQuads());
            MapManifest manifest = TileContextBootstrap.openPublisher().publish(
                    options.mapId(), options.mapName(), options.workDir(), merged,
                    pyramid.atlasPages(), options.publishDir());

            printSummary(options, manifest, pyramid, System.currentTimeMillis() - t0, out);
            return 0;
        } catch (OutOfMemoryError e) {
            // 内存不足：这里只做「说清楚原因」这一件事，别再做别的分配
            log.error("渲染内存不足: {}", options.mapId(), e);
            err.println("内存不足：窗口内区块的网格放不进当前堆（可用 -Pheap / render.heap 调大，或缩小范围）");
            return 4;
        } catch (Throwable e) {
            log.error("渲染失败", e);
            err.println("渲染失败: " + e);
            e.printStackTrace(err);
            return 1;
        }
    }

    /**
     * 确保有可用的模型几何来源，返回 models.json.gz 路径（null = 退回静态解析）。
     *
     * <p>约定 {@code packs[0]} 是原版 client jar、其余是 mod jar：采集需要「原版 jar 作兜底资源包 +
     * mod jar 列表 + worker 子进程 classpath」。产物缓存在 {@code <workDir>/models.json.gz}，
     * 命中缓存就不再采集。</p>
     *
     * <p>为什么默认采集：静态解析在 uvlock、元素旋转等处与原版有偏差，而采集拿到的是游戏自己烘焙的
     * 几何，按定义正确。成本约 1 分钟（本机 1.20.4 + 方块小镇实测 70 秒 / 413 万 quad）。</p>
     */
    private static Path ensureModels(RenderMapOptions options, PrintStream out) {
        if (options.modelsFile() != null) {
            if (!Files.isRegularFile(options.modelsFile())) {
                out.println("models 文件不存在: " + options.modelsFile());
                return null;
            }
            out.println("模型来源: 指定采集产物 " + options.modelsFile());
            return options.modelsFile();
        }
        Path cached = options.workDir().resolve("models.json.gz");
        if (Files.isRegularFile(cached)) {
            out.println("模型来源: 采集缓存 " + cached + "（命中缓存，跳过采集）");
            return cached;
        }
        if (options.skipHarvest()) {
            out.println("模型来源: 静态解析（skipHarvest；uvlock/旋转等处可能与原版有偏差）");
            return null;
        }
        if (options.packs().isEmpty() || options.workerClasspath().isEmpty()) {
            out.println("模型来源: 静态解析（缺 packs[0] 或 worker classpath，无法采集）");
            return null;
        }
        Path vanillaJar = options.packs().getFirst();
        List<Path> modJars = List.copyOf(options.packs().subList(1, options.packs().size()));
        out.printf("首次渲染：先采集模型几何（mc=%s loader=%s mods=%d，约 1 分钟）%n",
                options.mcVersion(), options.loaderVersion(), modJars.size());
        HarvestCliOptions harvest = new HarvestCliOptions(
                options.mcVersion(), options.loaderVersion(), options.workDir(), vanillaJar, modJars,
                options.workerClasspath(), options.workDir().resolve("models-cache"),
                30, false, false);
        HarvestModelsCli.run(harvest);
        if (!Files.isRegularFile(cached)) {
            out.println("采集未产出 models.json.gz，退回静态解析");
            return null;
        }
        return cached;
    }

    /**
     * 落盘逐格地表色（1 像素 = 1 方块）与地理信息，供后端全景渲染使用。
     *
     * <p>后端全景（服务端预渲染）需要「地表色 + 高度场」两样：高度场已由 LOD 阶段写入
     * {@code heightfield.bin}，这里补上颜色；两者合起来就能在服务端把整张地图渲染成一张图，
     * 客户端只加载那张图——这正是「把负载放在后端」的落点。</p>
     */
    private static void dumpSurface(RenderMapOptions options, LodOutcome pyramid,
                                    online.yudream.voxelith.tile.application.ImageCodec codec,
                                    PrintStream out) {
        AerialRaster surface = pyramid.surface();
        if (codec == null || surface == null || surface.isEmpty()) {
            return;
        }
        try {
            int[] argb = surface.toArgbGrid();
            Path png = options.workDir().resolve("surface.png");
            Files.write(png, codec.encodePng(surface.width(), surface.depth(), argb));
            Path meta = options.workDir().resolve("surface.json");
            Files.writeString(meta, "{\"originX\":" + surface.originX() + ",\"originZ\":"
                    + surface.originZ() + ",\"width\":" + surface.width()
                    + ",\"depth\":" + surface.depth() + "}", StandardCharsets.UTF_8);
            out.printf("地表栅格已落盘：surface.png %d×%d（后端全景渲染输入）%n",
                    surface.width(), surface.depth());
        } catch (IOException e) {
            throw new UncheckedIOException("写地表栅格失败", e);
        }
    }

    /**
     * LOD 图集页开关：{@code noLodAtlas=true} 时不给 ImageCodec，LOD 走纯顶点色（无航拍色图）。
     */
    private static online.yudream.voxelith.tile.application.ImageCodec lodImageCodec(RenderMapOptions options) {
        return options.lodAtlas() ? TileContextBootstrap.openImageCodec() : null;
    }

    /** 注入地图画后的网格表 + 统计。 */
    private record MeshesAndMapArt(Map<ChunkPos, BakedChunkMeshData> meshes, int frames, int maps) {
    }

    /**
     * 读窗口内所有展示框 → 注册地图贴图 → 把 1×1 面片补进对应区块网格。
     * 展示框是实体、地图在 data/map_*.dat，都不在方块数据里，这一步是地图画能显示的唯一入口。
     */
    private static MeshesAndMapArt injectMapArt(RenderMapOptions options, List<int[]> regionWindow,
                                               Map<ChunkPos, BakedChunkMeshData> meshes,
                                               MapArtInjector mapArt, PrintStream out) {
        AnvilMapArtReader reader = new AnvilMapArtReader(options.worldDir());
        List<MapArtFrame> frames = new ArrayList<>();
        Set<Integer> mapIds = new LinkedHashSet<>();
        for (int[] region : regionWindow) {
            for (MapArtFrame frame : reader.frames(new RegionPos(region[0], region[1]))) {
                frames.add(frame);
                mapIds.add(frame.mapId());
            }
        }
        int loaded = 0;
        for (int mapId : mapIds) {
            var colors = reader.mapColors(mapId);
            if (colors.isPresent()) {
                mapArt.register(mapId, colors.get());
                loaded++;
            }
        }
        Map<ChunkPos, BakedChunkMeshData> injected = mapArt.inject(frames, meshes);
        out.printf("%n地图画：展示框 %d 个，地图 %d 张（读到颜色 %d 张），已补入瓦片几何%n",
                frames.size(), mapIds.size(), loaded);
        return new MeshesAndMapArt(injected, frames.size(), loaded);
    }

    private static Path regionDir(RenderMapOptions options) {
        return switch (options.dimension()) {
            case "minecraft:the_nether" -> options.worldDir().resolve("DIM-1").resolve("region");
            case "minecraft:the_end" -> options.worldDir().resolve("DIM1").resolve("region");
            default -> options.worldDir().resolve("region");
        };
    }

    /**
     * 解析 region 窗口：**只保留实际存在、且与请求的方块范围有交集的 region 文件**。
     *
     * <p>未给出的边等同于「不限」。早先的实现是在 region 文件的极值之间铺满整个矩形网格，
     * 于是撒到 Z≈100 万方块外的哨站会把窗口撑成八万多个 region × 1024 个槽位
     * （实测燕理存档：8400 万次探测、40 秒纯扫描、上万行日志），而真正有内容的只有百来个文件。
     * 按文件枚举 + 按方块范围裁剪之后，小窗口的扫描开销与窗口本身成正比。</p>
     */
    static List<int[]> regionWindow(RenderMapOptions options, Path regionDir) {
        List<int[]> all = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            stream.forEach(p -> {
                Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    all.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取 region 目录失败: " + regionDir, e);
        }
        List<int[]> window = new ArrayList<>();
        for (int[] region : all) {
            if (options.regionX0() != null && region[0] < options.regionX0()) {
                continue;
            }
            if (options.regionX1() != null && region[0] > options.regionX1()) {
                continue;
            }
            if (options.regionZ0() != null && region[1] < options.regionZ0()) {
                continue;
            }
            if (options.regionZ1() != null && region[1] > options.regionZ1()) {
                continue;
            }
            if (!regionIntersects(region, options)) {
                continue;
            }
            window.add(region);
        }
        window.sort(Comparator.comparingInt((int[] r) -> r[0]).thenComparingInt(r -> r[1]));
        return window;
    }

    /**
     * 这个 region 的 512×512 方块范围与请求的方块范围有交集吗。
     *
     * <p>Region 窗口是按 512 方块取整的，只按窗口裁剪仍会把窗口内「离目标很远」的哨站一起扫进去；
     * 方块范围（框选出来的精确范围）才是真正的裁剪依据。</p>
     */
    private static boolean regionIntersects(int[] region, RenderMapOptions options) {
        int blockX0 = region[0] * 512;
        int blockX1 = blockX0 + 511;
        int blockZ0 = region[1] * 512;
        int blockZ1 = blockZ0 + 511;
        if (options.minX() != null && blockX1 < options.minX()) {
            return false;
        }
        if (options.maxX() != null && blockX0 > options.maxX()) {
            return false;
        }
        if (options.minZ() != null && blockZ1 < options.minZ()) {
            return false;
        }
        return options.maxZ() == null || blockZ0 <= options.maxZ();
    }

    /** 窗口内所有非空区块（sectionYs 非空即视为有内容）。 */
    private static List<ChunkPos> collectChunks(WorldBlockAccess world, List<int[]> regionWindow,
                                                RenderMapOptions options, PrintStream out) {
        List<ChunkPos> chunks = new ArrayList<>();
        int scanned = 0;
        int total = regionWindow.size() * 32 * 32;
        for (int[] region : regionWindow) {
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    ChunkPos pos = new ChunkPos(region[0] * 32 + cx, region[1] * 32 + cz);
                    scanned++;
                    if (!withinBounds(pos, options) || world.sectionYs(pos).length == 0) {
                        continue;
                    }
                    chunks.add(pos);
                }
            }
            if (scanned % 10240 == 0) {
                out.printf("  扫描区块 %d/%d，已找到非空 %d%n", scanned, total, chunks.size());
            }
        }
        chunks.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        return chunks;
    }

    /** 方块范围裁剪：区块与目标范围有交集就保留（region 窗口按 512 取整，必然多出边角）。 */
    private static boolean withinBounds(ChunkPos pos, RenderMapOptions options) {
        int cx0 = pos.x() * 16;
        int cx1 = cx0 + 15;
        int cz0 = pos.z() * 16;
        int cz1 = cz0 + 15;
        if (options.minX() != null && cx1 < options.minX()) {
            return false;
        }
        if (options.maxX() != null && cx0 > options.maxX()) {
            return false;
        }
        if (options.minZ() != null && cz1 < options.minZ()) {
            return false;
        }
        return options.maxZ() == null || cz0 <= options.maxZ();
    }

    /** 缺失方块普查：这是「哪些方块没渲染出来」的直接证据。 */
    private static void reportMissing(Map<String, Integer> missing, PrintStream out) {
        if (missing.isEmpty()) {
            out.println("missing 方块: 无");
            return;
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        missing.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        int total = missing.values().stream().mapToInt(Integer::intValue).sum();
        out.printf("missing 方块/模型 %d 种，共 %d 次（Top 25）:%n", missing.size(), total);
        sorted.forEach((id, n) -> out.printf("    %-58s %8d%n", id, n));
        out.println("    （这些方块没有几何，渲染为空洞；mod 方块需其 jar 在 packs 内且模型可静态解析）");
    }

    /**
     * 缺贴图普查：模型引用了、但资源包里找不到的贴图会落进品红兜底格。
     *
     * <p>最常见的成因是**资源包版本与世界版本不匹配**：贴图名跟着版本走（1.20.3 起
     * {@code grass} 改名 {@code short_grass}），拿 1.20.1 的 jar 解析 1.21 的世界，
     * 草地就会整片品红。所以这里把名字直接列出来，让人一眼看出该换哪个包。</p>
     */
    private static void reportMissingTextures(TileOutcome tiles, PrintStream out) {
        List<String> missing = tiles.missingTextures();
        if (missing.isEmpty() && tiles.untexturedQuads() == 0) {
            return;
        }
        if (!missing.isEmpty()) {
            out.printf("缺贴图 %d 种（已用品红兜底色代替）:%n", missing.size());
            missing.stream().limit(20).forEach(id -> out.println("    " + id));
            if (missing.size() > 20) {
                out.printf("    …… 另有 %d 种%n", missing.size() - 20);
            }
            out.println("    （多半是资源包版本与存档版本不一致：把对应版本的 client jar 放到");
            out.println("      .cache/minecraft/client-<版本>.jar 就会被自动匹配）");
        }
        if (tiles.untexturedQuads() > 0) {
            out.printf("无贴图面 %d 个（模型自身没引用贴图，按原版语义渲染为兜底格）%n",
                    tiles.untexturedQuads());
        }
    }

    private static void printSummary(RenderMapOptions options, MapManifest manifest,
                                     LodOutcome pyramid, long elapsedMillis, PrintStream out) {
        out.println();
        out.println("=== 渲染完成 ===");
        out.printf("  地图:     %s（%s）%n", manifest.mapId(), manifest.name());
        long lodTiles = pyramid == null
                ? manifest.tiles().stream().filter(t -> t.level() > 0).count()
                : pyramid.tiles().size();
        out.printf("  瓦片:     %d（其中 LOD %d）%n", manifest.tiles().size(), lodTiles);
        out.printf("  层级:     lodCount=%d%n", manifest.settings().lodCount());
        out.printf("  包围盒:   X[%.0f,%.0f] Y[%.0f,%.0f] Z[%.0f,%.0f]%n",
                manifest.boundsMin()[0], manifest.boundsMax()[0],
                manifest.boundsMin()[1], manifest.boundsMax()[1],
                manifest.boundsMin()[2], manifest.boundsMax()[2]);
        out.printf("  hires 图集: atlas.png %d² / %d 格%n",
                manifest.atlas().size(), manifest.atlas().textureCount());
        out.printf("  LOD 图集页: %d 层%n", manifest.lodAtlases().size());
        out.printf("  发布目录: %s%n", options.publishDir().resolve(manifest.mapId()).toAbsolutePath());
        out.printf("  总耗时:   %.1fs%n", elapsedMillis / 1000.0);
        out.println();
        out.println("下一步: gradle :apps:voxelith-server:bootRun 然后打开 http://127.0.0.1:8081");
    }

    private RenderMapCli() {
    }

    // ---------- 多遍渲染 ----------

    /**
     * 一批：本批的 region（LOD 增量合并按 region 记）、区块，以及 region 窗口数组
     * （地图画注入沿用 region 的形式）。
     */
    record Batch(List<RegionPos> regions, List<ChunkPos> chunks, List<int[]> window) {
    }

    /**
     * 多遍渲染：窗口按 region 切成若干批，逐批 bake→tile→lod，最后一次性发布。
     *
     * <p>单遍渲染的内存与窗口大小线性相关（bake 把窗口内全部区块的网格留在堆里），这是
     * 「整张图导不出来」的根本原因。分遍之后峰值只取决于单批大小，窗口可以任意大——
     * 代价是时间（每批都要重新读世界、重新烘几何）与磁盘（瓦片总量不变）。</p>
     *
     * <p>两个跨批一致性要点：</p>
     * <ol>
     *   <li><b>图集先打好、各批复用</b>：瓦片里的 UV 按图集布局算，各批各打一张会让先落盘的
     *       瓦片整体错位。贴图集合取采集产物的全表（超集），所以后面批次不会漏。</li>
     *   <li><b>LOD 层级按整窗口算好再逐批传</b>：层级取决于整图瓦片范围，各批各自「自动」
     *       会算出不同层级，接缝处会露空。</li>
     * </ol>
     */
    private static int renderInBatches(RenderMapOptions options, WorldBlockAccess world,
                                       ResolvedResourceCatalog catalog, List<ChunkPos> chunks,
                                       PrebakedQuadSource prebaked, PrintStream out, PrintStream err) {
        List<Batch> batches = planBatches(chunks, options.batchChunks());
        if (batches.isEmpty()) {
            err.println("多遍渲染：没有可渲染的区块");
            return 1;
        }
        out.printf("%n多遍渲染：窗口 %d 个非空区块 → %d 批（每批约 %d 区块、按 region 对齐；峰值内存只与单批相关）%n",
                chunks.size(), batches.size(), options.batchChunks());

        MapArtInjector mapArt = new MapArtInjector(new CatalogTexturePixelSource(catalog));
        Set<String> textures = new TreeSet<>(prebaked == null ? Set.of() : prebaked.textures());
        List<MapArtFrame> frames = readMapArt(options, mapArt, allRegionWindows(batches), out);
        // 静态解析（无采集产物）时贴图表拿不到，只能靠「用到的都在里面」这一超集兜底：
        // 记下提示，让用户知道缺贴图的可能来源
        if (prebaked == null) {
            out.println("  注意：没有采集产物（models.json.gz），共享图集无法预知全部贴图；"
                    + "缺贴图的方块会落品红兜底格");
        }
        GenerateTilesUseCase tiles = TileContextBootstrap.openGenerator(catalog, mapArt);
        AtlasReuse atlas = tiles.packSharedAtlas(options.workDir(), textures);
        out.printf("共享图集已就绪：%d 格（%d px），%d 批全部复用它%n",
                atlas.layout().cellIndex().size(), atlas.layout().pixelSize(), batches.size());

        GenerateLodPyramidUseCase lod = new GenerateLodPyramidUseCase(
                TileContextBootstrap.openTextureColorSampler(catalog),
                TileContextBootstrap.openVertexColorTileExporter(),
                lodImageCodec(options));
        HeightfieldStore store = new FileHeightfieldStore(
                options.workDir().resolve("heightfield.bin"));
        int lodLevel = lodLevelForWindow(chunks);
        out.printf("LOD 层级按整图算好：%d 层（各批统一，避免接缝露空）%n", lodLevel);

        List<TileOutcome.TileSummary> all = new ArrayList<>();
        List<String> missingTextures = new ArrayList<>();
        int untextured = 0;
        long t0 = System.currentTimeMillis();
        int done = 0;
        for (int i = 0; i < batches.size(); i++) {
            Batch batch = batches.get(i);
            long tBatch = System.currentTimeMillis();
            out.printf("多遍渲染 批次 %d/%d：本批 %d 区块（累计 %d/%d）%n",
                    i + 1, batches.size(), batch.chunks().size(),
                    Math.min(chunks.size(), done + batch.chunks().size()), chunks.size());

            BakeChunksUseCase bake = new BakeChunksUseCase(
                    catalog, world, new FileBakeArtifactSink(), prebaked);
            BakeOutcome baked = bake.bake(new BakeCommand(
                    batch.chunks(), options.workDir(), 0, options.minY()));
            Map<ChunkPos, BakedChunkMeshData> meshes = BakedMeshMapper.toData(baked.meshes());
            // 地图画：本批只注入落在本批区块里的展示框（贴图在共享图集里已经备好）
            if (!frames.isEmpty()) {
                List<MapArtFrame> batchFrames = framesInBatch(frames, batch);
                if (!batchFrames.isEmpty()) {
                    meshes = mapArt.inject(batchFrames, meshes);
                }
            }
            TileOutcome hires = tiles.generate(new TileCommand(
                    meshes, options.workDir(), EncodeOptions.sharedAtlas(), atlas));
            all.addAll(hires.tiles());
            missingTextures.addAll(hires.missingTextures());
            untextured += hires.untexturedQuads();

            LodOutcome pyramid = lod.generate(new LodCommand(
                    meshes, options.workDir(), lodLevel, store, batch.regions()));
            all.addAll(pyramid.tiles());

            // 显式丢引用：这一批的网格（最占内存的那份）到此为止，下一批重新开始
            meshes.clear();
            done += batch.chunks().size();
            out.printf("  本批完成：区块 %d，hires 瓦片 %d，LOD 瓦片 %d，用时 %.1fs%n",
                    batch.chunks().size(), hires.tiles().size(), pyramid.tiles().size(),
                    (System.currentTimeMillis() - tBatch) / 1000.0);
        }

        // 各批的瓦片摘要合在一起发布：清单的包围盒、hires 图集、LOD 层级都按整图算
        TileOutcome merged = new TileOutcome(
                all,
                options.workDir().resolve("atlas.png"),
                options.workDir().resolve("tile-report.json"),
                atlas.layout().cellIndex().size(),
                atlas.layout().pixelSize(),
                List.copyOf(new TreeSet<>(missingTextures)),
                untextured);
        out.printf("合并发布：hires 瓦片 %d，LOD 瓦片 %d%n",
                all.size() - all.stream().filter(t -> t.pos().level() > 0).count(),
                all.stream().filter(t -> t.pos().level() > 0).count());
        MapManifest manifest = TileContextBootstrap.openPublisher().publish(
                options.mapId(), options.mapName(), options.workDir(), merged,
                List.of(), options.publishDir());
        out.println("  注意：多遍渲染的 LOD 瓦片逐片内嵌色图（不分层图集页），"
                + "地表栅格/全景输入请另行生成");
        printSummary(options, manifest, null, System.currentTimeMillis() - t0, out);
        return 0;
    }

    /** 本批涉及的展示框（按展示框所在区块是否在本批内过滤）。 */
    private static List<MapArtFrame> framesInBatch(List<MapArtFrame> frames, Batch batch) {
        Set<Long> keys = new HashSet<>();
        for (ChunkPos pos : batch.chunks()) {
            keys.add(chunkKey(pos.x(), pos.z()));
        }
        List<MapArtFrame> result = new ArrayList<>();
        for (MapArtFrame frame : frames) {
            if (keys.contains(chunkKey(frame.x() >> 4, frame.z() >> 4))) {
                result.add(frame);
            }
        }
        return result;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** 预读窗口内全部地图画：贴图并进共享图集，帧留给各批注入。 */
    private static List<MapArtFrame> readMapArt(RenderMapOptions options, MapArtInjector mapArt,
                                                List<int[]> regionWindow, PrintStream out) {
        AnvilMapArtReader reader = new AnvilMapArtReader(options.worldDir());
        List<MapArtFrame> frames = new ArrayList<>();
        Set<Integer> mapIds = new LinkedHashSet<>();
        for (int[] region : regionWindow) {
            for (MapArtFrame frame : reader.frames(new RegionPos(region[0], region[1]))) {
                frames.add(frame);
                mapIds.add(frame.mapId());
            }
        }
        int loaded = 0;
        for (int mapId : mapIds) {
            var colors = reader.mapColors(mapId);
            if (colors.isPresent()) {
                mapArt.register(mapId, colors.get());
                loaded++;
            }
        }
        if (!frames.isEmpty()) {
            out.printf("地图画：展示框 %d 个，地图 %d 张（读到颜色 %d 张），贴图已并入共享图集%n",
                    frames.size(), mapIds.size(), loaded);
        }
        return frames;
    }

    private static List<int[]> allRegionWindows(List<Batch> batches) {
        List<int[]> window = new ArrayList<>();
        for (Batch batch : batches) {
            window.addAll(batch.window());
        }
        return window;
    }

    /**
     * 按 region 切批：region 是渲染的自然单位（LOD 的增量合并也按 region 记），
     * 一批里尽量放整块 region，超过 {@code batchChunks} 就收口。
     */
    static List<Batch> planBatches(List<ChunkPos> chunks, int batchChunks) {
        Map<RegionPos, List<ChunkPos>> byRegion = new LinkedHashMap<>();
        for (ChunkPos pos : chunks) {
            byRegion.computeIfAbsent(new RegionPos(pos.x() >> 5, pos.z() >> 5), k -> new ArrayList<>())
                    .add(pos);
        }
        List<Batch> batches = new ArrayList<>();
        List<RegionPos> regions = new ArrayList<>();
        List<ChunkPos> current = new ArrayList<>();
        for (Map.Entry<RegionPos, List<ChunkPos>> entry : byRegion.entrySet()) {
            regions.add(entry.getKey());
            current.addAll(entry.getValue());
            if (current.size() >= batchChunks) {
                batches.add(batchOf(regions, current));
                regions = new ArrayList<>();
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            batches.add(batchOf(regions, current));
        }
        return List.copyOf(batches);
    }

    private static Batch batchOf(List<RegionPos> regions, List<ChunkPos> chunks) {
        List<int[]> window = new ArrayList<>(regions.size());
        for (RegionPos region : regions) {
            window.add(new int[]{region.x(), region.z()});
        }
        return new Batch(List.copyOf(regions), List.copyOf(chunks), List.copyOf(window));
    }

    /**
     * 按整窗口的瓦片范围估一个 LOD 层级，交给每一批用同一个值。
     *
     * <p>层级偏大没坏处（多一层粗瓦片），偏小会在远处露空，所以这里按窗口范围算、宁可多一层。</p>
     */
    static int lodLevelForWindow(List<ChunkPos> chunks) {
        int minCx = Integer.MAX_VALUE;
        int maxCx = Integer.MIN_VALUE;
        int minCz = Integer.MAX_VALUE;
        int maxCz = Integer.MIN_VALUE;
        for (ChunkPos pos : chunks) {
            minCx = Math.min(minCx, pos.x());
            maxCx = Math.max(maxCx, pos.x());
            minCz = Math.min(minCz, pos.z());
            maxCz = Math.max(maxCz, pos.z());
        }
        // 1 个 hires 瓦片 = 2×2 区块；之后每层 2×2 聚合
        int tx = Math.max(1, (maxCx - minCx + 1) / 2);
        int tz = Math.max(1, (maxCz - minCz + 1) / 2);
        int level = 1;
        while ((tx > 2 || tz > 2) && level < 12) {
            tx = (tx + 1) / 2;
            tz = (tz + 1) / 2;
            level++;
        }
        return level;
    }
}
