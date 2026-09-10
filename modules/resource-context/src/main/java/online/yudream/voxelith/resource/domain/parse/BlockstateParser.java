package online.yudream.voxelith.resource.domain.parse;

import online.yudream.voxelith.resource.domain.model.BlockstateDefinition;

/**
 * blockstate JSON 解析端口。实现位于 infrastructure 层（gson）。
 */
public interface BlockstateParser {

    BlockstateDefinition parse(String json);
}
