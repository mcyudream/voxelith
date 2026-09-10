package online.yudream.voxelith.resource.domain.pack;

import online.yudream.voxelith.sharedkernel.vo.Identifier;

import java.util.Optional;
import java.util.Set;

/**
 * 资源包端口：对 zip/jar/目录等载体的领域抽象。实现位于 infrastructure 层。
 * 只暴露渲染链路关心的三类资产（blockstate / model / texture）。
 */
public interface ResourcePack {

    /** 包标识（文件名或目录名），用于报告与日志。 */
    String packId();

    /** 读取方块 blockstate 定义，如 stone → assets/{ns}/blockstates/stone.json。 */
    Optional<PackResource> blockstate(Identifier block);

    /** 读取模型定义，如 block/stone → assets/{ns}/models/block/stone.json。 */
    Optional<PackResource> model(Identifier model);

    /** 读取贴图，如 block/stone → assets/{ns}/textures/block/stone.png。 */
    Optional<PackResource> texture(Identifier texture);

    /**
     * 读取包内任意路径的原始资源（相对包根，含 assets/ 与 data/ 两侧）。
     * 供 blockstate/model/texture 之外的资产使用（群系定义、colormap 等）。
     */
    default Optional<PackResource> raw(String path) {
        return Optional.empty();
    }

    /** 列出包内全部 blockstate 对应的方块标识（命名空间保留）。 */
    Set<Identifier> listBlocksWithBlockstate();
}
