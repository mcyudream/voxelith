package online.yudream.voxelith.orchestration.infrastructure.shard;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import online.yudream.voxelith.orchestration.domain.PipelineStage;
import online.yudream.voxelith.orchestration.domain.ShardJob;
import online.yudream.voxelith.orchestration.domain.ShardJobState;
import online.yudream.voxelith.orchestration.domain.ShardQueuePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件系统分片队列：{@code {root}/{stage}/{shard}.json} 记录状态，{@code .lock} 抢占。
 *
 * <p>并发安全靠两个文件系统本身就保证原子的原语：</p>
 * <ol>
 *   <li>独占创建（{@code Files.createFile} = O_CREAT|O_EXCL）：多进程同时抢同一片只有一个成功，
 *       这是「谁能领这片」的唯一裁决点。注意不能用「写临时文件再 rename」——POSIX 的 rename
 *       会静默覆盖，两个 worker 会同时以为自己拿到了；</li>
 *   <li>租约：作业文件里记 leaseUntil。worker 崩溃后锁文件会留下，但租约一过别的 worker
 *       就删锁重抢——超时语义在作业文件里，不在锁文件上。</li>
 * </ol>
 *
 * <p>作业文件写入走 tmp + 原子替换，读方永远不会看到半截 JSON。</p>
 */
public final class FileShardQueue implements ShardQueuePort {

    private static final Gson GSON = new Gson();

    private final Path root;

    public FileShardQueue(Path root) {
        this.root = root;
    }

    @Override
    public void enqueue(PipelineStage stage, List<String> shards) {
        try {
            Files.createDirectories(stageDir(stage));
            for (String shard : shards) {
                Path file = jobFile(stage, shard);
                if (Files.isRegularFile(file)) {
                    ShardJob existing = readJob(file);
                    if (existing != null && existing.state() == ShardJobState.DONE) {
                        continue;   // 已完成：不因为重跑入队而丢掉产物
                    }
                    if (existing != null && existing.state() == ShardJobState.CLAIMED
                            && !existing.leaseExpired(System.currentTimeMillis())) {
                        continue;   // 别人正在跑：不抢
                    }
                }
                writeJob(file, ShardJob.queued(stage, shard));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("分片入队失败: " + stage, e);
        }
    }

    @Override
    public Optional<ShardJob> claim(String workerId, Duration lease, PipelineStage stage) {
        long now = System.currentTimeMillis();
        // 租约时长由调用方决定（Worker 池默认 30 分钟）；这里只挡住 0/负值，
        // 免得写成「立刻过期」的作业让别人抢走正在跑的分片
        long leaseUntil = now + Math.max(1, lease.toMillis());
        // 只扫本阶段的目录：分片键（r.X.Z）在各阶段是重名的，越阶段领取等于抢错活
        Path dir = stageDir(stage);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        for (Path file : listJobFiles(dir)) {
            ShardJob job = readJob(file);
            if (job == null || job.terminal()) {
                continue;
            }
            if (job.state() == ShardJobState.CLAIMED && !job.leaseExpired(now)) {
                continue;
            }
            if (!acquireLock(lockFile(stage, job.shard()), job)) {
                continue;
            }
            ShardJob claimed = job.claimed(workerId, leaseUntil);
            writeJob(file, claimed);
            return Optional.of(claimed);
        }
        return Optional.empty();
    }

    @Override
    public void complete(PipelineStage stage, String shard, List<String> artifacts) {
        ShardJob job = require(stage, shard);
        writeJob(jobFile(stage, shard), job.done(artifacts));
        releaseLock(lockFile(stage, shard));
    }

    @Override
    public void fail(PipelineStage stage, String shard, String error) {
        ShardJob job = require(stage, shard);
        writeJob(jobFile(stage, shard), job.failed(error));
        releaseLock(lockFile(stage, shard));
    }

    @Override
    public List<ShardJob> jobs(PipelineStage stage) {
        Path dir = stageDir(stage);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ShardJob> jobs = new ArrayList<>();
        for (Path file : listJobFiles(dir)) {
            ShardJob job = readJob(file);
            if (job != null) {
                jobs.add(job);
            }
        }
        jobs.sort(Comparator.comparing(ShardJob::shard));
        return List.copyOf(jobs);
    }

    @Override
    public void clear(PipelineStage stage) {
        Path dir = stageDir(stage);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清空分片队列失败: " + stage, e);
        }
    }

    @Override
    public void reset(PipelineStage stage, String shard) {
        try {
            Files.deleteIfExists(jobFile(stage, shard));
            Files.deleteIfExists(lockFile(stage, shard));
        } catch (IOException e) {
            throw new UncheckedIOException("重置分片失败: " + stage + "/" + shard, e);
        }
    }

    /** 取锁：真正的独占点（createFile 在 POSIX 与 Windows 上都是原子 O_EXCL）。 */
    private boolean acquireLock(Path lock, ShardJob job) {
        try {
            Files.createDirectories(lock.getParent());
            Files.createFile(lock);
            return true;
        } catch (FileAlreadyExistsException taken) {
            if (!job.leaseExpired(System.currentTimeMillis())) {
                return false;
            }
            // 上一个 worker 崩了：删掉它的锁再抢一次（删+建之间可能被别人插队，由 createFile 裁决）
            try {
                Files.deleteIfExists(lock);
                Files.createFile(lock);
                return true;
            } catch (IOException ignored) {
                return false;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("抢分片锁失败: " + lock, e);
        }
    }

    private ShardJob require(PipelineStage stage, String shard) {
        ShardJob job = readJob(jobFile(stage, shard));
        if (job == null) {
            throw new IllegalStateException("分片不存在: " + stage + "/" + shard);
        }
        return job;
    }

    private static void releaseLock(Path lock) {
        try {
            Files.deleteIfExists(lock);
        } catch (IOException e) {
            throw new UncheckedIOException("释放分片锁失败: " + lock, e);
        }
    }

    private static List<Path> listJobFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列出分片失败: " + dir, e);
        }
    }

    private Path stageDir(PipelineStage stage) {
        return root.resolve(stage.name().toLowerCase(Locale.ROOT));
    }

    private Path jobFile(PipelineStage stage, String shard) {
        return stageDir(stage).resolve(fileName(shard) + ".json");
    }

    private Path lockFile(PipelineStage stage, String shard) {
        return stageDir(stage).resolve(fileName(shard) + ".lock");
    }

    /** 分片键来自 region 名（{@code r.0.0}）：只保留安全字符，防止越界写。 */
    private static String fileName(String shard) {
        return shard.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ShardJob readJob(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        // Windows 上「原子替换作业文件」的瞬间，并发读可能拿到共享冲突（AccessDenied）——
        // 那是**瞬时**错误，绝不能让整个 worker 池炸掉。重试几次再放弃；
        // 仍读不到（文件被人手工改坏/删除）就当这片不可读跳过。
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonObject json = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> artifacts = new ArrayList<>();
                if (json.has("artifacts")) {
                    for (JsonElement element : json.getAsJsonArray("artifacts")) {
                        artifacts.add(element.getAsString());
                    }
                }
                return new ShardJob(
                        PipelineStage.valueOf(json.get("stage").getAsString()),
                        json.get("shard").getAsString(),
                        ShardJobState.valueOf(json.get("state").getAsString()),
                        json.has("workerId") && !json.get("workerId").isJsonNull()
                                ? json.get("workerId").getAsString() : null,
                        json.has("leaseUntil") ? json.get("leaseUntil").getAsLong() : 0,
                        json.has("attempts") ? json.get("attempts").getAsInt() : 0,
                        artifacts,
                        json.has("error") && !json.get("error").isJsonNull()
                                ? json.get("error").getAsString() : null);
            } catch (IOException e) {
                sleepQuietly(attempt);
            } catch (RuntimeException e) {
                // 内容坏掉（JSON 截断/字段缺失）：重试也没用，当不可读跳过
                return null;
            }
        }
        return null;
    }

    /** 读失败后的短暂退避（10/20/40ms）：等并发的写入方把文件放回来。 */
    private static void sleepQuietly(int attempt) {
        try {
            Thread.sleep(10L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeJob(Path file, ShardJob job) {
        JsonObject json = new JsonObject();
        json.addProperty("stage", job.stage().name());
        json.addProperty("shard", job.shard());
        json.addProperty("state", job.state().name());
        if (job.workerId() != null) {
            json.addProperty("workerId", job.workerId());
        }
        json.addProperty("leaseUntil", job.leaseUntilEpochMs());
        json.addProperty("attempts", job.attempts());
        JsonArray artifacts = new JsonArray();
        job.artifacts().forEach(artifacts::add);
        json.add("artifacts", artifacts);
        if (job.error() != null) {
            json.addProperty("error", job.error());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写分片失败: " + file, e);
        }
    }
}
