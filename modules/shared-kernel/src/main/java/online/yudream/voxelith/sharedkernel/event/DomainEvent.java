package online.yudream.voxelith.sharedkernel.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件基接口。跨限界上下文协作只允许通过 application 用例或领域事件。
 */
public interface DomainEvent {

    default String eventId() {
        return UUID.randomUUID().toString();
    }

    default Instant occurredAt() {
        return Instant.now();
    }
}
