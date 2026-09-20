package online.yudream.voxelith.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.voxelith.bake.application.BakeCommand;
import online.yudream.voxelith.server.upload.PreviewService;
import online.yudream.voxelith.server.upload.RenderInputs;
import online.yudream.voxelith.server.upload.RenderJobService;
import online.yudream.voxelith.server.upload.WorldStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 上传 / 预览 / 渲染任务的组合根。
 *
 * <p>这三件事都要「资源包栈 + 采集产物 + work-dir / publish-dir」这套输入，
 * 且都服务于「上传存档 → 二维框选 → 按范围渲染」这一条链路，所以装配集中在这里。</p>
 */
@Configuration
public class UploadRenderConfig {

    /**
     * 存档仓库。搜索根决定「只上传一个 level.dat」时能认出哪些存档：
     * 除了 .minecraft/saves 与已登记本机存档的上级目录，还能用 world-roots 追加
     * （例：{@code A:\maps,~/.minecraft/saves}）。
     */
    @Bean
    public WorldStore worldStore(@Value("${yudream.voxelith.upload-dir:./work/uploads}") String uploadDir,
                                 @Value("${yudream.voxelith.upload.world-roots:}") String worldRoots,
                                 ObjectMapper objectMapper) {
        return new WorldStore(Path.of(uploadDir), objectMapper, splitPaths(worldRoots));
    }

    /**
     * 渲染输入解析：资源包（原版 client jar + mod jar）与采集产物。
     *
     * <p>包列表留空时自动发现：先找 work-dir 上溯到的 {@code .cache/minecraft/client-*.jar}，
     * 再找 {@code ~/.minecraft/versions}——本机没配也能直接跑起来。</p>
     */
    @Bean(destroyMethod = "close")
    public RenderInputs renderInputs(
            @Value("${yudream.voxelith.work-dir:./work}") String workDir,
            @Value("${yudream.voxelith.render.packs:}") String packs,
            @Value("${yudream.voxelith.render.models-file:}") String modelsFile,
            @Value("${yudream.voxelith.render.mc-version:1.20.1}") String mcVersion,
            @Value("${yudream.voxelith.render.loader-version:0.16.14}") String loaderVersion,
            @Value("${yudream.voxelith.render.worker-classpath:}") String workerClasspath) {
        return new RenderInputs(
                Path.of(workDir),
                splitPaths(packs),
                modelsFile == null || modelsFile.isBlank() ? null : Path.of(modelsFile.trim()),
                splitPaths(workerClasspath),
                mcVersion,
                loaderVersion);
    }

    @Bean
    public PreviewService previewService(
            WorldStore worldStore,
            RenderInputs renderInputs,
            ObjectMapper objectMapper,
            @Value("${yudream.voxelith.work-dir:./work}") String workDir,
            @Value("${yudream.voxelith.preview.max-size:1600}") int maxSize,
            @Value("${yudream.voxelith.preview.threads:0}") int threads) {
        return new PreviewService(worldStore, renderInputs, objectMapper,
                Path.of(workDir).resolve("preview"), maxSize, threads);
    }

    @Bean
    public RenderJobService renderJobService(
            WorldStore worldStore,
            RenderInputs renderInputs,
            @Value("${yudream.voxelith.publish-dir:./data/maps}") String publishDir,
            @Value("${yudream.voxelith.render.min-y:}") String minY,
            @Value("${yudream.voxelith.render.max-level:0}") int maxLevel,
            @Value("${yudream.voxelith.render.lod-atlas:true}") boolean lodAtlas,
            @Value("${yudream.voxelith.render.meshopt:true}") boolean meshopt,
            @Value("${yudream.voxelith.render.max-chunks:0}") int maxChunks,
            @Value("${yudream.voxelith.render.batch-chunks:2048}") int batchChunks,
            @Value("${yudream.voxelith.render.heap:}") String renderHeap) {
        // min-y 留空 = 不限制：存档高度差异极大（地表可能在 y≈0，也可能在 y≈200），
        // 服务端不该替用户猜；前端会按预览算出的地表高度给出建议默认值
        return new RenderJobService(worldStore, renderInputs, Path.of(publishDir),
                minY == null || minY.isBlank() ? BakeCommand.NO_MIN_Y : Integer.parseInt(minY.trim()),
                maxLevel, lodAtlas, meshopt, maxChunks, batchChunks, heapBytes(renderHeap));
    }

    /**
     * 渲染子进程的堆上限。留空 = 按物理内存自动取 60%（上限 16G、下限 2G）：
     * 渲染是本项目的内存大户（CLI 一直要求 {@code -Pheap=8g}），给足堆才跑得动校园级窗口，
     * 同时把剩下的内存留给系统与 web 进程本身。
     *
     * @param raw 形如 {@code 8g} / {@code 8192m} / {@code 8192}（不带单位按 MB）
     */
    static long heapBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return autoHeapBytes();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[a-z]+$", "");
        long unit = 1024L * 1024;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith("g") || lower.endsWith("gb")) {
            unit = 1024L * 1024 * 1024;
        }
        try {
            return Math.max(256L * 1024 * 1024, Long.parseLong(value.trim()) * unit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("render.heap 需要形如 8g / 8192m 的值，收到: " + raw);
        }
    }

    /** 物理内存的 60%（2G 起步、16G 封顶）；拿不到物理内存时按 web 进程堆的 2 倍估。 */
    private static long autoHeapBytes() {
        long ram;
        try {
            ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
        } catch (Throwable t) {
            ram = Runtime.getRuntime().maxMemory() * 2;
        }
        return Math.min(16L * 1024 * 1024 * 1024,
                Math.max(2L * 1024 * 1024 * 1024, ram * 6 / 10));
    }

    /** 逗号/分号分隔的路径列表（跳过空白项）。 */
    private static List<Path> splitPaths(String raw) {
        List<Path> paths = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return paths;
        }
        for (String piece : raw.split("[,;]")) {
            if (!piece.isBlank()) {
                paths.add(Path.of(piece.trim()));
            }
        }
        return paths;
    }
}
