package online.yudream.voxelith.resource.domain.model;

import online.yudream.voxelith.sharedkernel.exception.DomainException;

/** 模型解析失败（缺失/循环继承/非法内容）。 */
public class ModelResolutionException extends DomainException {

    public ModelResolutionException(String code, String message) {
        super(code, message);
    }

    public static ModelResolutionException missing(String modelId) {
        return new ModelResolutionException("resource.model.missing", "模型不存在: " + modelId);
    }

    public static ModelResolutionException cycle(String chain) {
        return new ModelResolutionException("resource.model.cycle", "模型继承循环: " + chain);
    }
}
