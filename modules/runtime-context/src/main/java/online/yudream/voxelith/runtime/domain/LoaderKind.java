package online.yudream.voxelith.runtime.domain;

/** mod 加载器种类。首个适配目标为 FABRIC（见 docs/adr/0001）。 */
public enum LoaderKind {
    FABRIC,
    FORGE
}
