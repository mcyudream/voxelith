package online.yudream.voxelith.orchestration.domain;

/**
 * 增量渲染端口：按 region 重跑 bake→tile→lod，返回清单补丁（sha1 替换/删除 + 新瓦片摘要）。
 * 由组合根装配 bake/tile/lod 用例；orchestration 只编排，不直连对方 domain。
 */
public interface IncrementalRenderPort {

    IncrementalPatch rerender(IncrementalJob job) throws Exception;
}
