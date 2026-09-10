package online.yudream.voxelith.orchestration.domain;

import java.util.Map;

/**
 * 增量渲染端口：按 region 重跑 bake→tile→lod，返回被更新瓦片的 url → sha1。
 * 由组合根装配 bake/tile/lod 用例；orchestration 只编排，不直连对方 domain。
 */
public interface IncrementalRenderPort {

    /**
     * @return 被更新瓦片相对 url（与清单 TileEntry.url 对齐）→ 新 sha1；
     *         值为空串表示该瓦片被删除
     */
    Map<String, String> rerender(IncrementalJob job) throws Exception;
}
