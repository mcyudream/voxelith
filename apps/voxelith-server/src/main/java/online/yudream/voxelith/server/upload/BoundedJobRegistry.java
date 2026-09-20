package online.yudream.voxelith.server.upload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界任务表：**已完成**的作业只保留最近若干个，运行中的一个都不淘汰。
 *
 * <p>为什么需要：任务记录里带着渲染日志（每任务最多 {@code MAX_LOG_LINES} 行），
 * 服务端长期运行、反复提交渲染时，只 put 不淘汰会让内存只增不减。
 * 淘汰的判据是「完成顺序」（FIFO），与是否被前端拉取过无关——
 * 前端拿旧任务的日志本来就该在任务还在列表里时拿。</p>
 *
 * @param <T> 作业类型
 */
final class BoundedJobRegistry<T> {

    private final int maxFinished;
    private final Map<String, T> jobs = new ConcurrentHashMap<>();
    private final Deque<String> finished = new ArrayDeque<>();

    BoundedJobRegistry(int maxFinished) {
        this.maxFinished = Math.max(1, maxFinished);
    }

    void put(String id, T job) {
        jobs.put(id, job);
    }

    /** 标记完成并入队等待淘汰（重复调用幂等）。 */
    void markFinished(String id) {
        if (!jobs.containsKey(id)) {
            return;
        }
        synchronized (finished) {
            if (!finished.contains(id)) {
                finished.addLast(id);
            }
            while (finished.size() > maxFinished) {
                String evicted = finished.pollFirst();
                if (evicted != null && !evicted.equals(id)) {
                    jobs.remove(evicted);
                } else if (evicted != null) {
                    // 本次完成的这个也不该被自己挤掉：放回去，等下一次淘汰
                    finished.addLast(evicted);
                    break;
                }
            }
        }
    }

    Optional<T> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    List<T> list() {
        return new ArrayList<>(jobs.values());
    }

    int size() {
        return jobs.size();
    }

    int retainedFinishedCount() {
        synchronized (finished) {
            return finished.size();
        }
    }
}
