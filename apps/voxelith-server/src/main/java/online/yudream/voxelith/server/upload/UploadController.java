package online.yudream.voxelith.server.upload;

import online.yudream.voxelith.world.infrastructure.anvil.AnvilWorldReader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 上传 / 预览 / 渲染接口。
 *
 * <pre>
 * GET    /api/uploads                      已登记存档列表
 * POST   /api/uploads/archive              上传存档压缩包，或只上传一个 level.dat（multipart）
 * POST   /api/uploads/local                登记本机存档目录（不复制文件）
 * GET    /api/uploads/{id}                 单个存档详情（含各维度 region 统计）
 * DELETE /api/uploads/{id}                 删除登记（本机目录来源只删记录）
 * POST   /api/uploads/{id}/preview         启动（或复用）地表预览
 * GET    /api/uploads/{id}/preview         预览状态
 * GET    /api/uploads/{id}/preview.png     预览图
 * GET    /api/render/defaults              渲染默认值与可选资源包
 * POST   /api/render/jobs                  提交渲染任务
 * GET    /api/render/jobs                  任务列表
 * GET    /api/render/jobs/{id}             任务详情
 * GET    /api/render/jobs/{id}/log?since=  任务日志增量
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private final WorldStore store;
    private final PreviewService previews;
    private final RenderJobService jobs;
    private final RenderInputs inputs;
    private final Path publishDir;

    public UploadController(WorldStore store, PreviewService previews, RenderJobService jobs,
                            RenderInputs inputs,
                            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir) {
        this.store = store;
        this.previews = previews;
        this.jobs = jobs;
        this.inputs = inputs;
        this.publishDir = Path.of(publishDir).toAbsolutePath().normalize();
    }

    // ---------- 存档登记 ----------

    @GetMapping("/uploads")
    public List<WorldUpload> list() {
        return store.list();
    }

    @GetMapping("/uploads/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        WorldUpload upload = require(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload", upload);
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String dimension : upload.dimensions()) {
            dimensions.put(dimension, store.stats(upload, dimension));
        }
        result.put("dimensionStats", dimensions);
        return result;
    }

    /**
     * 上传一份存档：.zip 压缩包按包解，单个 level.dat 则在本机认出它所属的存档目录。
     * 两种格式共用一个入口，前端不必先分辨用户拖进来的是什么（浏览器也给不了文件路径）。
     */
    @PostMapping(value = "/uploads/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorldUpload uploadArchive(@RequestPart("file") MultipartFile file,
                                     @RequestParam(value = "name", required = false) String name) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空");
        }
        try (var in = file.getInputStream()) {
            return store.registerUpload(in, file.getOriginalFilename(), name);
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传流失败", e);
        }
    }

    @PostMapping("/uploads/local")
    public WorldUpload registerLocal(@RequestBody LocalDirRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("请填写存档目录路径");
        }
        Path dir = Path.of(request.path().trim());
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在: " + dir);
        }
        return store.registerLocalDir(dir, request.name());
    }

    @DeleteMapping("/uploads/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean removed = store.delete(id);
        int previewFiles = previews.deleteCache(id);
        return Map.of("removed", removed);
    }

    /** 本机存档目录登记请求。 */
    public record LocalDirRequest(String path, String name) {
    }

    // ---------- 地表预览 ----------

    @PostMapping("/uploads/{id}/preview")
    public PreviewService.PreviewStatus startPreview(
            @PathVariable String id,
            @RequestParam(value = "dimension", defaultValue = "minecraft:overworld") String dimension) {
        return previews.start(id, dimension);
    }

    @GetMapping("/uploads/{id}/preview")
    public PreviewService.PreviewStatus previewStatus(
            @PathVariable String id,
            @RequestParam(value = "dimension", defaultValue = "minecraft:overworld") String dimension) {
        require(id);
        return previews.status(id, dimension);
    }

    @GetMapping("/uploads/{id}/preview.png")
    public ResponseEntity<byte[]> previewImage(
            @PathVariable String id,
            @RequestParam(value = "dimension", defaultValue = "minecraft:overworld") String dimension,
            @RequestParam(value = "step", required = false) Integer step) {
        require(id);
        Optional<Path> image = previews.imagePath(id, dimension, step);
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(image.get());
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    // 预览按「存档 × 维度」缓存到磁盘，内容不变；但重新上传同名存档会换 id
                    .header("Cache-Control", "public, max-age=86400")
                    .body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("读取预览图失败", e);
        }
    }

    /**
     * 按需渲染一个方块矩形窗口（可缩放框选的取图入口）。
     * 结果按「窗口 × step」缓存在服务端，同一区域第二次直接返回。
     */
    @GetMapping("/uploads/{id}/window.png")
    public ResponseEntity<byte[]> windowImage(
            @PathVariable String id,
            @RequestParam(value = "dimension", defaultValue = "minecraft:overworld") String dimension,
            @RequestParam("minX") int minX,
            @RequestParam("minZ") int minZ,
            @RequestParam(value = "size", defaultValue = "512") int size,
            @RequestParam(value = "step", defaultValue = "1") int step) {
        require(id);
        if (size < 16 || size > 1024 || step < 1 || step > 1 << 20) {
            throw new IllegalArgumentException("size 须在 16..1024、step 须为正");
        }
        try {
            byte[] bytes = Files.readAllBytes(previews.windowImage(id, dimension, minX, minZ, size, step));
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "public, max-age=86400")
                    .body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("读取窗口预览失败", e);
        }
    }

    // ---------- 已发布地图 ----------

    /** 删除已发布地图：发布目录（瓦片/清单/全景）与渲染工作目录一并清掉。 */
    @DeleteMapping("/maps/{mapId}")
    public Map<String, Object> deleteMap(@PathVariable String mapId) {
        boolean removed = jobs.deletePublished(mapId);
        return Map.of("removed", removed, "mapId", mapId);
    }

    // ---------- 渲染任务 ----------

    @GetMapping("/render/defaults")
    public Map<String, Object> renderDefaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> packs = inputs.packs().stream().map(Path::toString).toList();
        result.put("packs", packs);
        result.put("modelsFile", inputs.modelsFile().map(Path::toString).orElse(null));
        result.put("clientJarCandidates", inputs.discoverAllClientJars().stream()
                .map(Path::toString).toList());
        result.put("workDir", inputs.workDir().toString());
        // 前端据此在提交前就把「这个范围太大」说清楚，而不是等服务端拒绝
        result.put("maxChunks", jobs.effectiveMaxChunks());
        // 超过该建议值时服务端会自动分遍渲染（每批这么多区块）
        result.put("batchChunks", jobs.batchChunks());
        return result;
    }

    @PostMapping("/render/jobs")
    public RenderJobService.RenderJob submit(@RequestBody RenderRequest request) {
        return jobs.submit(request);
    }

    @GetMapping("/render/jobs")
    public List<RenderJobService.RenderJob> listJobs() {
        return jobs.list();
    }

    @GetMapping("/render/jobs/{id}")
    public RenderJobService.RenderJob job(@PathVariable String id) {
        return jobs.find(id).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    @GetMapping("/render/jobs/{id}/log")
    public Map<String, Object> jobLog(@PathVariable String id,
                                      @RequestParam(value = "since", defaultValue = "0") int since) {
        RenderJobService.RenderJob job = jobs.find(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
        return Map.of(
                "lines", jobs.logSince(id, since),
                "logStart", job.logStart(),
                "logSize", job.logSize());
    }

    // ---------- 辅助 ----------

    private WorldUpload require(String id) {
        return store.find(id).orElseThrow(() -> new IllegalArgumentException("存档不存在: " + id));
    }

    // ---------- 异常映射 ----------

    /** 参数类错误按 400 返回，前端直接把 message 显示给用户。 */
    @ExceptionHandler({IllegalArgumentException.class, UncheckedIOException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", message));
    }

    // ---------- 本机目录探测 ----------

    /** 扫描某个根目录下形似存档的目录（含 level.dat），供前端给出候选路径。 */
    @GetMapping("/local-worlds")
    public List<Map<String, Object>> localCandidates(
            @RequestParam(value = "root", required = false) String root,
            @RequestParam(value = "depth", defaultValue = "2") int depth) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (root == null || root.isBlank()) {
            return result;
        }
        Path base = Path.of(root);
        if (!Files.isDirectory(base)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(base, Math.max(1, Math.min(depth, 4)))) {
            walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("level.dat")))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(50)
                    .forEach(p -> result.add(Map.of(
                            "path", p.toAbsolutePath().toString(),
                            "name", p.getFileName().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描目录失败: " + base, e);
        }
        return result;
    }
}
