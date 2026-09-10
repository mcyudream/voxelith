package online.yudream.voxelith.sharedkernel.event;

@FunctionalInterface
public interface DomainEventHandler<E extends DomainEvent> {

    void onEvent(E event);
}
