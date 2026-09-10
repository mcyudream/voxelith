package online.yudream.voxelith.sharedkernel.event;

/**
 * 领域事件总线端口。由基础设施层实现（一期进程内同步分发，二期可替换为消息队列）。
 */
public interface DomainEventBus {

    void publish(DomainEvent event);

    <E extends DomainEvent> void subscribe(Class<E> eventType, DomainEventHandler<E> handler);
}
