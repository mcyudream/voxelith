package online.yudream.voxelith.runtime.infrastructure.process;

/**
 * 父子进程文件协议常量（ADR 0001）。
 * 与 modules/runtime-worker 中 HarvestWorkerMain 的实现保持同步——两侧都依赖这些文件名与字段名。
 */
public final class WorkerProtocol {

    /** 父进程写入的运行规格文件（workDir 下）。 */
    public static final String SPEC_FILE = "spec.json";
    /** worker 写入的报告文件（workDir 下）。 */
    public static final String RESULT_FILE = "worker-result.json";

    public static final String FIELD_OK = "ok";
    public static final String FIELD_CHECKS = "checks";
    public static final String FIELD_HARVESTED = "harvested";
    public static final String FIELD_FAILURES = "failures";
    public static final String FIELD_DURATION = "durationMillis";
    public static final String FIELD_STATES_EXPORTED = "statesExported";
    public static final String FIELD_QUADS_EXPORTED = "quadsExported";
    public static final String FIELD_MODELS_FILE = "modelsFile";

    /** spec.json 可选字段：provision 产出的 MC 游戏主 jar 绝对路径。 */
    public static final String SPEC_GAME_JAR = "gameJar";

    private WorkerProtocol() {
    }
}
