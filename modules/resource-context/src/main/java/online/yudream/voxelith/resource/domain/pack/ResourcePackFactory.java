package online.yudream.voxelith.resource.domain.pack;

import java.nio.file.Path;

/**
 * 资源包工厂端口：按载体（jar/zip/目录）打开资源包。实现位于 infrastructure 层。
 */
public interface ResourcePackFactory {

    /**
     * @param source   包路径（.jar/.zip 文件或解压目录）
     * @param priority 叠加优先级，数值越大越优先（后加载的资源包覆盖先加载的）
     */
    ResourcePack open(Path source, int priority);
}
