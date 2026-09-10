package online.yudream.voxelith.resource.application.dto;

/**
 * 模型引用的跨上下文传输形态（烘焙链路消费）。
 */
public record ModelVariantData(String model, int x, int y, boolean uvLock, int weight) {
}
