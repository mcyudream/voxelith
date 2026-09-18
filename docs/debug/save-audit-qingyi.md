# 存档核查报告：250323西南科大青义存档

> 目的：用这个「无 mod 方块」的存档做 LOD 色块 / 4fps 问题的复现测试。
> 核查方式：直接解析 `level.dat` 与 `region/*.mca`（Anvil 格式），未运行渲染管线。

---

## 一、结论速览

| 项 | 结果 |
|---|---|
| 版本 | **DataVersion 3955 = Minecraft 1.21.1** |
| 区块规模 | **95,293 个非空区块**（十万级，正是 Phase 6 的目标量级） |
| 世界尺寸 | region 网格 12×11 → 约 **6144 × 5632 方块** |
| 方块种类 | **122 种**（纯原版，未见 mod 方块） |
| 区块格式 | 现代 paletted 格式（1.18+），**后端 `ModernChunkParser` 可解析** ✅ |
| ⚠ 版本错配 | 存档 1.21.1，但**模型集按 1.20.1 构建**（ADR 0001）→ **3 个方块缺失** |
| ⚠ 文件异常 | 123 个 region 文件里 **12 个是 0 字节** |

---

## 二、存档概况

```
LevelName     : 250323西南科大青义
DataVersion   : 3955  (MC 1.21.1)
WasModded     : 1
ServerBrands  : ["fabric", ...]
Dimensions    : overworld / the_nether / the_end（后两者 region 为空）
总大小        : 374 MB
```

| 维度 | 统计 |
|---|---|
| region 文件 | 123 个（其中 12 个 0 字节 → 111 个可用） |
| 非空区块 | 95,293 |
| 平均每 region | 858 区块 |
| X region | −2 ~ 9（宽 12） |
| Z region | −2 ~ 8（深 11） |
| Y 截面 | −5 ~ 19（即 y = −80 ~ 319，现代世界高度） |

对比 README 里写的「默认存档 7866 区块」——**这个存档大了一个数量级**，属于「十万级区块压测」场景。

> 你 HUD 上的 `瓦片 2176/27947` 与这个存档的体量吻合（约 2.8 万瓦片），所以上一轮截图基本可以确认就是这张图。

---

## 三、⚠ 关键发现：模型集版本错配

### 3.1 后端解析层：兼容 ✅

`ModernChunkParser` 声明支持「1.18+，1.20.1 验证」。1.21.1 的区块结构（`sections[].block_states.palette` + `data` 长数组）与 1.18 起一致，**实测 95,293 个区块全部解析成功**，Y 截面 −5~19 也正常。→ **解析层没问题。**

### 3.2 模型/贴图层：3 个方块不匹配 ❌

模型集来源是 **1.20.1**（ADR 0001：headless 运行时首个适配目标 1.20.1 Fabric；`RuntimeSpec` 的测试与默认值全部是 `"1.20.1"`）。
而这张存档是 **1.21.1**。逐个核对 122 种方块后，有 3 种在 1.20.1 里**不存在**：

| 方块 | 引入/变更版本 | 在 1.20.1 中的情况 |
|---|---|---|
| `minecraft:polished_tuff_wall` | 1.21（凝灰岩家族） | 不存在 |
| `minecraft:tuff_brick_wall` | 1.21（凝灰岩砖） | 不存在 |
| `minecraft:short_grass` | 1.20.3 由 `minecraft:grass` **改名**而来 | 叫 `minecraft:grass` |

### 3.3 这会怎么表现？——与色块问题直接相关

按 ADR 0002 的既定策略：

> 新贴图（新方块）本期不扩图集，**映射到品红兜底格**。

所以这 3 个方块会走**品红兜底格**（`#FF00FF`，红紫色）。而且因为 LOD 的航拍色图是把方块顶面颜色**溅到栅格再做盒式平均**，品红会被平均进 LOD 色图 → **LOD 上会出现偏红的色斑**。

这与「大面积红/青色块」有一定相关性，但**大概率不是全部原因**（只有 3 种方块，且 `short_grass` 是小投影面积的植物，按 `collectSamples` 的规则面积 < 0.5 会被排除在航拍栅格外）。

> 值得注意：`GenerateLodPyramidUseCase.collectSamples` 的注释里就写着——
> 「花/火把/草等 XZ 投影面积远小于半格的细面不进高度场，**避免高空俯视出现红色噪点**」。
> 说明「红色噪点」是**曾经出现过**的问题，这里已经做过一轮规避。如果现在的红色块比「噪点」大得多，那它更可能是纹理绑定/图集问题（见诊断文档 §1）。

### 3.4 建议

**优先把模型集切到 1.21.1**（`VanillaClientPackProvider.ensureClientJar("1.21.1")` 是按版本号下载的，改一个入参即可），让模型集与存档对齐，再谈复现。否则测试结果里会混入版本错配的噪声。

---

## 四、⚠ 12 个 0 字节 region 文件

```
r.-2.2.mca  r.-2.3.mca  r.-2.4.mca  r.-2.5.mca
r.1.8.mca   r.2.8.mca   r.3.8.mca   r.4.8.mca
r.5.-2.mca  r.5.8.mca   (共 12 个，size = 0)
```

这些文件**没有 8192 字节的头部**。请确认 `AnvilRegionReader` 对 0 字节文件是**跳过**而不是抛异常——否则 scan 阶段会直接失败。这也是一个值得顺手补的健壮性测试用例。

---

## 五、为什么我这边跑不了完整管线

已确认的环境阻塞项：

| 阻塞 | 说明 |
|---|---|
| 无 Gradle | wrapper 要求 `gradle-8.14-bin`，本地只有残缺的 `.part` 文件；下载被代理 MITM 证书问题挡住（`PKIX path building failed`） |
| 无独立 gradle | 全盘（A:/C:）未找到 gradle 安装 |
| 需联网取原版 jar | `VanillaClientPackProvider` / `HttpRuntimeProvisioner` 要从 Mojang 下载 client jar |
| 无管线入口 | 仓库内没有 CLI / Gradle 任务，全量管线靠 jshell 驱动（README 已注明） |
| 无既有产物 | `voxelith/work`、`voxelith/data` 都不存在，没有可对照的基线 |

所以这一轮我交付的是**存档侧的静态核查**（能离线完成、且结论确定），而不是渲染复现。

---

## 六、建议的测试步骤（在你机器上）

### 6.1 先对齐版本

把模型集从 1.20.1 切到 **1.21.1**，避免品红兜底格污染测试结果。

### 6.2 用这个存档跑一次全量管线

```
world-dir : A:/liu23/Documents/Graduation project/map/250323西南科大青义存档
dimension : minecraft:overworld
map-id    : qingyi
```

或在 `application.yml` 里走增量路径（先跑一次全量生成 `atlas-layout.json` + 清单，增量才有图集可复用）：

```yaml
yudream:
  voxelith:
    incremental:
      enabled: true
      world-dir: A:/liu23/Documents/Graduation project/map/250323西南科大青义存档
      pack-dir: <1.21.1 client.jar 或资源包路径>
      map-id: qingyi
```

### 6.3 出图后立刻按诊断文档 §2 抓四个数

```
renderer.info.memory.textures      # 纹理对象数（预期：LOD 一瓦片一纹理 → 2000+）
renderer.info.render.calls         # draw call（判断 4fps 是绑定瓶颈还是提交瓶颈）
LOD 私有纹理数                      # 应与 LOD 瓦片数同量级
材质绑定误判数                       # >0 即命中「LOD 被当成 hires」的主嫌疑
```

### 6.4 再做隔离开关

`__layer.hiresOnly()` / `lodOnly()`（诊断文档 §5），5 分钟内锁定色块归属。

---

## 七、本次核查用到的可复用方法

- `level.dat` → 解 gzip NBT，读 `DataVersion` / `LevelName` / `WasModded` / `ServerBrands`
- `region/*.mca` 头部 4096 字节 → 每 4 字节判 `offset≠0 && count≠0` 即非空区块（**不解析 NBT，秒级统计全图规模**）
- 区块调色板 → 只取 `sections[].block_states.palette[].Name`，统计方块种类并与目标模型集版本对照
