package online.yudream.voxelith.runtime.application;

/**
 * 模型来源：优先 headless 运行时采集（真实 BakedModel，含 mod 方块），
 * 失败时降级为 jar 资源静态解析（BlueMap 式，见 ADR 0001 风险对策）。
 */
public enum ModelSource {

    /** headless worker 子进程采集成功（models.json.gz）。 */
    RUNTIME_HARVEST,

    /** runtime 采集失败，回退静态解析 blockstate/model JSON。 */
    STATIC_FALLBACK
}
