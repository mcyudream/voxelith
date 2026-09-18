package online.yudream.voxelith.server.upload;

import java.time.Instant;
import java.util.List;

/**
 * 已登记的存档（上传解包或本机路径指向）。
 *
 * @param id          上传 id（目录名，URL 安全）
 * @param name        展示名
 * @param worldDir    存档根目录绝对路径（含 level.dat）
 * @param versionName MC 版本名（来自 level.dat）
 * @param dataVersion 存档 DataVersion
 * @param dimensions  可用维度 id 列表（存在 region 目录才算）
 * @param source      LOCAL_DIR = 指向本机已有目录；ARCHIVE = 上传压缩包解包
 * @param createdAt   登记时间
 */
public record WorldUpload(
        String id,
        String name,
        String worldDir,
        String versionName,
        int dataVersion,
        List<String> dimensions,
        String source,
        Instant createdAt) {

    public static final String SOURCE_LOCAL_DIR = "LOCAL_DIR";
    public static final String SOURCE_ARCHIVE = "ARCHIVE";
}
