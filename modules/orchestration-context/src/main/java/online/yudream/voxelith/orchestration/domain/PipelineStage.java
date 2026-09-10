package online.yudream.voxelith.orchestration.domain;

import java.util.List;

/**
 * 渲染管线阶段（resolve→scan→bake→tile→lod→manifest，严格按声明序执行）。
 */
public enum PipelineStage {
    RESOLVE,
    SCAN,
    BAKE,
    TILE,
    LOD,
    MANIFEST;

    public static List<PipelineStage> ordered() {
        return List.of(values());
    }
}
