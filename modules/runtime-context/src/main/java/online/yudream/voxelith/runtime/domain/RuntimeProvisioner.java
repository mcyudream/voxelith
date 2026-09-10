package online.yudream.voxelith.runtime.domain;

import java.nio.file.Path;

/**
 * 运行时 provision 端口：按 spec 下载/缓存 MC 游戏 jar、加载器与全部依赖，
 * 供进程隔离启动器组装 worker 子进程 classpath。
 */
public interface RuntimeProvisioner {

    /**
     * 幂等：cacheDir 下已存在且哈希匹配的文件不重复下载。
     *
     * @throws IllegalStateException 下载失败或版本不存在
     */
    ProvisionedRuntime provision(RuntimeSpec spec, Path cacheDir);
}
