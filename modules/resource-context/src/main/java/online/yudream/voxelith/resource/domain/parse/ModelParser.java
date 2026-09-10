package online.yudream.voxelith.resource.domain.parse;

import online.yudream.voxelith.resource.domain.model.ModelDefinition;
import online.yudream.voxelith.sharedkernel.vo.Identifier;

/**
 * 模型 JSON 解析端口。实现位于 infrastructure 层（gson）。
 */
public interface ModelParser {

    ModelDefinition parse(Identifier id, String json);
}
