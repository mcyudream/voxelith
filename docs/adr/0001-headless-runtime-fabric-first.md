# ADR 0001：Headless 运行时 —— 进程隔离 + Fabric 优先 + LWJGL stub

- 状态：已接受（2026-09-10，Phase 5 启动）
- 上下文：runtime-context 需要执行 mod 代码以采集真实 BakedModel（计划 Phase 5）。

## 决策

1. **进程隔离**：mod/MC 代码在独立 JVM 子进程（worker）中运行，与 Spring Boot 服务端完全隔离。
   父子之间以「落盘文件 + 退出码」通信：父进程写 `spec.json`（运行规格），worker 写
   `worker-result.json`（采集报告）与模型产物目录。不用 socket/RPC——worker 可能崩溃或
   死锁，文件协议天然可断点续查、可事后审计。
2. **首个加载器适配目标：1.20.1 Fabric**。
   - fabric-loader 是一个干净的、无安装器的类加载器库（Knot），可直接以普通 JVM
     classpath 启动；Forge 需要 installer 产出 + bootstraplauncher 模块层（JPMS
     module-path）魔法，headless 化路径长得多。
   - 模型采集的目标 API（ResourceManager / ModelLoader / BakedModel）是原版代码，
     与加载器无关；Fabric 路线先把"无头执行 mod 代码"链路打通，Forge 适配器后续按
     同一 SPI 插拔。
   - HeadlessMC（MIT）已验证同一结论：其 Forge/Fabric/NeoForge 支持中 Fabric 适配最薄。
3. **LWJGL stub 手写最小集起步，签名以真实 LWJGL 3.3.x 为准**：
   worker 模块内置 `org.lwjgl.*` stub（GLFW / GL / GL11 / GL30 / system），
   所有函数返回安全默认值，不做任何 native 调用。后续用脚本从真实 LWJGL jar
   反射生成完整覆盖（MC 1.20.1 客户端实际链接触及面）。

## 后果

- 正：服务端零 native 依赖、worker 崩溃不影响主进程、协议文件即审计记录。
- 负：跨进程调试链路长；stub 覆盖不全时表现为 NoSuchMethodError，需靠
  self-test 模式逐轮补齐。
- 约束：worker 模块（`modules/runtime-worker`）不进入 ArchUnit 四层扫描
  （它是子进程入口，非服务端上下文）；其类不依赖任何服务端模块。

## 补充（2026-09-10，模型采集落地后）

4. **LWJGL 从"全 stub"修正为"部分真实"**：窗口/GL/音频模块（lwjgl-glfw/opengl/
   openal/tinyfd/jemalloc）仍以 stub 取代；`org.lwjgl` core 与 `lwjgl-stb` 及其
   natives 必须保留真实 jar——SpriteLoader 的 PNG 解码与 MemoryUtil 堆外内存
   走真 native（从 classpath jar 内加载，无需系统安装）。
5. **降级兜底**：runtime 采集失败（worker 崩溃/超时/校验不过）时，
   `HarvestWithFallbackUseCase`（runtime-context application）回退到
   resource-context 的静态 resolve 链路（BlueMap 式 jar 资源解析），并在
   workDir 写 `model-acquisition.json` 标记来源（RUNTIME_HARVEST /
   STATIC_FALLBACK）与 runtime 失败明细。组合根在 apps/voxelith-server
   `RuntimeHarvestConfig`。
