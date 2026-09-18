package online.yudream.voxelith.server.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.voxelith.server.preview.WorldPreviewRenderer;
import online.yudream.voxelith.tile.application.ImageCodec;
import online.yudream.voxelith.tile.infrastructure.bootstrap.TileContextBootstrap;
import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 地表预览服务：为「框选渲染范围」提供一张二维俯视图。
 *
 * <p>生成按存档规模可能是几十秒级（要逐区块读 NBT），所以做成异步 + 进度轮询 + 磁盘缓存：
 * 同一个「存档 × 维度」只算一次，之后刷新页面直接命中。缓存落在
 * {@code <work-dir>/preview/<uploadId>__<dimension>.png} 与同名 {@code .json}（地理信息）。</p>
 */
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    /** 预览状态机。 */
    public enum State {
        /** 还没开始 */
        IDLE,
        /** 生成中 */
        RUNNING,
        /** 可访问 */
        READY,
        /** 失败 */
        FAILED
    }

    /**
     * 地理信息：前端据此把像素换算成方块坐标，并把框选结果反解成渲染范围。
     *
     * @param version       缓存 schema 版本；旧版本 JSON 会被判为过期并重新生成
     * @param minSurfaceY/maxSurfaceY 抽样列的地表高度范围；无数据时为 Integer.MIN_VALUE。
     *        前端用它给「最低渲染高度 minY」一个合理默认值——存档高度差异极大，
     *        写死的默认值会把整片几何裁掉（本机实测有地表只在 y≈0~6 的存档）。
     */
    public record PreviewMeta(
            int version,
            String dimension,
            int originX,
            int originZ,
            int step,
            int width,
            int depth,
            int blockMinX,
            int blockMaxX,
            int blockMinZ,
            int blockMaxZ,
            int regionCount,
            long chunkCount,
            int minSurfaceY,
            int maxSurfaceY,
            List<int[]> regions,
            List<LevelRef> levels) {

        /** 一级预览图（金字塔按 step 从粗到细排列）；前端按 step 拉取对应层级。 */
        public record LevelRef(int step, int width, int depth) {
        }
    }

    /** 预览地理信息的缓存 schema 版本；字段增减时 +1，旧 JSON 自动重算。 */
    public static final int META_VERSION = 3;

    /**
     * @param state   IDLE / RUNNING / READY / FAILED
     * @param done    已处理 region 数
     * @param total   总 region 数（RUNNING 前为 0）
     * @param message 失败原因或提示
     * @param meta    READY 时有值
     */
    public record PreviewStatus(State state, int done, int total, String message, PreviewMeta meta) {
    }

    private final WorldStore store;
    private final RenderInputs inputs;
    private final Path previewDir;
    private final int maxSize;
    private final int threads;
    private final ImageCodec codec = TileContextBootstrap.openImageCodec();
    private final ObjectMapper objectMapper;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voxelith-preview-job");
        t.setDaemon(true);
        return t;
    });

    /** 一个「存档 × 维度」的预览槽位。 */
    private static final class Slot {
        volatile State state = State.IDLE;
        volatile int done;
        volatile int total;
        volatile String message = "";
        volatile PreviewMeta meta;
    }

    public PreviewService(WorldStore store, RenderInputs inputs, ObjectMapper objectMapper,
                          Path previewDir, int maxSize, int threads) {
        this.store = store;
        this.inputs = inputs;
        this.objectMapper = objectMapper;
        this.previewDir = previewDir.toAbsolutePath().normalize();
        this.maxSize = maxSize;
        this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
    }

    /** 查询状态；磁盘上有缓存时直接判定 READY（重启后不必重算）。 */
    public PreviewStatus status(String uploadId, String dimension) {
        String key = key(uploadId, dimension);
        Slot slot = slots.get(key);
        if (slot == null) {
            slot = new Slot();
            PreviewMeta cached = readMeta(uploadId, dimension);
            if (cached != null && Files.isRegularFile(imagePathFor(uploadId, dimension))) {
                slot.state = State.READY;
                slot.meta = cached;
            }
            slots.put(key, slot);
        }
        return new PreviewStatus(slot.state, slot.done, slot.total, slot.message, slot.meta);
    }

    /** 启动（或复用）预览生成。重复调用是幂等的。 */
    public PreviewStatus start(String uploadId, String dimension) {
        WorldUpload upload = store.find(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("存档不存在: " + uploadId));
        if (!upload.dimensions().contains(dimension)) {
            throw new IllegalArgumentException("该存档没有维度: " + dimension);
        }
        String key = key(uploadId, dimension);
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            if (slot.state == State.RUNNING || slot.state == State.READY) {
                return new PreviewStatus(slot.state, slot.done, slot.total, slot.message, slot.meta);
            }
            slot.state = State.RUNNING;
            slot.done = 0;
            slot.total = 0;
            slot.message = "正在读取区块…";
            pool.submit(() -> generate(upload, dimension, slot));
            return new PreviewStatus(slot.state, slot.done, slot.total, slot.message, slot.meta);
        }
    }

    /**
     * 按需渲染一个方块矩形窗口（可缩放框选的取图入口），带磁盘缓存：
     * 同一「窗口 × step」第二次直接命中，不再扫描。
     *
     * @return PNG 路径（渲染或缓存命中）
     */
    public Path windowImage(String uploadId, String dimension,
                            int minX, int minZ, int size, int step) {
        WorldUpload upload = store.find(uploadId)
                .orElseThrow(() -> new IllegalArgumentException("存档不存在: " + uploadId));
        Path file = previewDir.resolve(uploadId + "__" + safe(dimension)
                + "__win_" + minX + "_" + minZ + "_" + size + "_" + step + ".png");
        if (Files.isRegularFile(file)) {
            return file;
        }
        WorldPreviewRenderer renderer = new WorldPreviewRenderer(
                new online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader(),
                inputs.catalog());
        WorldPreviewRenderer.WindowPreview preview = renderer.renderWindow(
                Path.of(upload.worldDir()), dimension, minX, minZ, size, step, 0, null);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, codec.encodePng(size, size, preview.argb()));
        } catch (IOException e) {
            throw new UncheckedIOException("写窗口预览失败: " + file, e);
        }
        return file;
    }

    /** 预览图路径（最细一级；未就绪时为空）。 */
    public Optional<Path> imagePath(String uploadId, String dimension) {
        Path png = imagePathFor(uploadId, dimension);
        return Files.isRegularFile(png) ? Optional.of(png) : Optional.empty();
    }

    /**
     * 指定步长的预览图路径（金字塔逐级落盘后按 step 取用；未就绪时为空）。
     *
     * @param step 像素/方块比；null 或未命中某级时返回最细一级
     */
    public Optional<Path> imagePath(String uploadId, String dimension, Integer step) {
        if (step == null) {
            return imagePath(uploadId, dimension);
        }
        Path file = previewDir.resolve(uploadId + "__" + safe(dimension) + "__s" + step + ".png");
        return Files.isRegularFile(file) ? Optional.of(file) : imagePath(uploadId, dimension);
    }

    private void generate(WorldUpload upload, String dimension, Slot slot) {
        long t0 = System.currentTimeMillis();
        try {
            Files.createDirectories(previewDir);
            WorldPreviewRenderer renderer = new WorldPreviewRenderer(
                    new AnvilWorldReader(), inputs.catalog());
            WorldPreviewRenderer.Preview preview = renderer.render(
                    Path.of(upload.worldDir()), dimension, maxSize, threads,
                    (done, total) -> {
                        slot.done = done;
                        slot.total = total;
                        slot.message = "已解析 " + done + "/" + total + " 个 region";
                    });
            if (preview == null) {
                fail(slot, "该维度没有区块数据");
                return;
            }
            // 金字塔逐级落盘：最细一级沿用旧文件名（兼容），粗级带步长后缀
            List<PreviewMeta.LevelRef> levels = new ArrayList<>();
            for (WorldPreviewRenderer.Preview.Level level : preview.pyramid()) {
                Path file = level.step() == preview.step()
                        ? imagePathFor(upload.id(), dimension)
                        : previewDir.resolve(upload.id() + "__" + safe(dimension)
                                + "__s" + level.step() + ".png");
                Files.write(file, codec.encodePng(level.width(), level.depth(), level.argb()));
                levels.add(new PreviewMeta.LevelRef(level.step(), level.width(), level.depth()));
            }
            PreviewMeta meta = new PreviewMeta(META_VERSION, dimension,
                    preview.originX(), preview.originZ(), preview.step(),
                    preview.width(), preview.depth(),
                    preview.blockMinX(), preview.blockMaxX(),
                    preview.blockMinZ(), preview.blockMaxZ(),
                    preview.regions().size(), preview.chunkCount(),
                    preview.minSurfaceY(), preview.maxSurfaceY(),
                    preview.regions(), List.copyOf(levels));
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(metaPathFor(upload.id(), dimension).toFile(), meta);
            slot.meta = meta;
            slot.message = "";
            slot.state = State.READY;
            log.info("地表预览完成：{} / {}，{}×{} 像素（step={}），{} 个 region，耗时 {}ms",
                    upload.id(), dimension, preview.width(), preview.depth(), preview.step(),
                    preview.regions().size(), System.currentTimeMillis() - t0);
        } catch (RuntimeException | IOException e) {
            log.error("地表预览失败: {} / {}", upload.id(), dimension, e);
            fail(slot, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void fail(Slot slot, String message) {
        slot.state = State.FAILED;
        slot.message = message;
    }

    private PreviewMeta readMeta(String uploadId, String dimension) {
        Path file = metaPathFor(uploadId, dimension);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            PreviewMeta meta = objectMapper.readValue(file.toFile(), PreviewMeta.class);
            if (meta.version() != META_VERSION) {
                log.info("预览地理信息版本过期（{} != {}），将重新生成: {}", meta.version(), META_VERSION, file);
                return null;
            }
            return meta;
        } catch (IOException e) {
            log.warn("损坏的预览地理信息，将重新生成: {}", file);
            return null;
        }
    }

    private Path imagePathFor(String uploadId, String dimension) {
        return previewDir.resolve(uploadId + "__" + safe(dimension) + ".png");
    }

    private Path metaPathFor(String uploadId, String dimension) {
        return previewDir.resolve(uploadId + "__" + safe(dimension) + ".json");
    }

    private static String key(String uploadId, String dimension) {
        return uploadId + "@" + dimension;
    }

    /**
     * 清掉某个上传的全部预览缓存（删登记存档时调用），避免孤儿文件常驻磁盘。
     */
    public int deleteCache(String uploadId) {
        List<Path> doomed = new ArrayList<>();
        try (var stream = Files.list(previewDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(uploadId + "__"))
                    .forEach(doomed::add);
        } catch (IOException e) {
            log.warn("扫描预览缓存失败: {}", previewDir, e);
        }
        int removed = 0;
        for (Path file : doomed) {
            try {
                Files.deleteIfExists(file);
                removed++;
            } catch (IOException e) {
                log.warn("删除预览缓存失败: {}", file, e);
            }
        }
        return removed;
    }

    private static String safe(String dimension) {
        return dimension.replace(':', '_').replace('/', '_');
    }
}
