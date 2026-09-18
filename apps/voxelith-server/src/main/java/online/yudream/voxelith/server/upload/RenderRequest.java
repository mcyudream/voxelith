package online.yudream.voxelith.server.upload;

import java.util.List;

/**
 * 渲染任务提交参数（前端框选结果 → 管线参数）。
 *
 * @param uploadId   已登记存档的 id
 * @param mapId      发布地图 id（留空取存档 id）
 * @param mapName    展示名（留空取存档名）
 * @param dimension  维度 id（默认主世界）
 * @param minX/maxX  渲染方块范围 X（含端点，来自二维地图框选）
 * @param minZ/maxZ  渲染方块范围 Z（含端点）
 * @param minY       最低渲染高度（留空取服务端默认；低于它的方块不参与网格化）
 * @param maxLevel   LOD 最高层级（0 = 自动）
 * @param packs      资源包路径（低 → 高优先级）；留空取服务端默认与自动发现
 * @param modelsFile runtime 采集产物 models.json.gz；留空自动探测
 * @param lodAtlas   是否生成 LOD 分层图集页
 */
public record RenderRequest(
        String uploadId,
        String mapId,
        String mapName,
        String dimension,
        Integer minX,
        Integer maxX,
        Integer minZ,
        Integer maxZ,
        Integer minY,
        Integer maxLevel,
        List<String> packs,
        String modelsFile,
        Boolean lodAtlas) {
}
