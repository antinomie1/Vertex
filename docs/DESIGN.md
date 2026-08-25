# Vertex 主设计文档（DESIGN.md · 终稿 v5）

> **文档地位**: 单一事实源。取代链: `design-audit.md`(结论) → `design.md`(蓝图) → `design-v3.md`(拓扑) → `design-v4.md`(执行) → **本文**(集成+补全)。其余 docs/* 为证据附录。
> 语言 Kotlin · LWJGL 3.4.2 · Fabric · MC 26.2 Vulkan 后端 · 首位平台 RADV/RDNA2。

---

## 1. 系统拓扑（v3 协同驻留单设备）

```
Blaze3D VkDevice（被注入扩展/特性后共用）
├─ [游戏] HUD/UI 提交流（我们窗口之后，同队列顺序衔接）
└─ [Vertex] 自有 command pool ×(L×T+N) ── 单次批量 submit/帧
      阴影CB* │ 地形CB(摊销) │ 动态CB(逐帧) │ post链CB ──→ 直写游戏主目标图像（零拷贝）
```

- **注入点**: Blaze3D 后端设备创建处 mixin，向扩展表追加所需项、向 feature pNext 链追加。检测失败 → 自动 Tier 0（仅后链），绝不硬崩。
- 必需注入: `descriptorIndexing+updateAfterBind`、`timelineSemaphore`、`multiDrawIndirect(+count)`；预期已开（不重复注入）: dynamicRendering、sync2。
- 挂接点: 世界渲染结束、无打开 pass 处；深度在此之前自行持有（我们的 gbuffer 深度），不依赖游戏深度（其随即被清）。
- 坐标约定全程 GL 方向，无 Y 翻转。

## 2. 逐族路由（Tier 协商）

| 族 | Tier 2 全接管 | 降档条件 |
|---|---|---|
| terrain_solid/cutout/cutout_mipped | ✅ | Sodium 冲突→T1 |
| terrain_solid(water) 半透明 | ✅ 逐帧路径 | 排序 mod 冲突→T1 |
| entities/block/particles/textured/lit | ✅ | BufferSource 捕获失败→T1 |
| hand / hand_water | ✅ | — |
| sky/skytextured/sun/moon/stars/clouds/weather | ✅ | — |
| 外来世界渲染(DH/Replay 等) | — | 自动 T1/T0 + 日志 |

Tier 1: 游戏(管线短路 mixin)绘制进加宽目标，材质 id 缺失照 Vitrail 行为。Tier 0: 仅 composite/final 后链。

## 3. M2 翻译规则总表（完备版）

### 3.1 词法/预处理
include 解析（包根→维度目录层叠）→ `#define` 展开 → 包选项代入 `#if/#ifdef` 常量折叠（选项哈希入缓存键）→ 注释剥离保留行号映射（错误报告用 `packfile:line` 格式）。

### 3.2 内建替换表
| 旧构造 | 替换 |
|---|---|
| `attribute vecN x` | `layout(location=L) in`（L 由顶点格式表定） |
| `varying` | vertex 出块/out + frag in 块（成对改名 `v_`） |
| `gl_Vertex` | 输入 `a_pos`(量化解码) |
| `gl_Normal` | 八面体 23bit 解码（octX11+octY11+手性1） |
| `gl_Color` | 输入色或 separateAo 双字之一（按 pack 指令） |
| `gl_MultiTexCoord0` | 图集 uv（含亚纹素 bias 常量） |
| `gl_MultiTexCoord1` | 光照原始值 → `gl_TextureMatrix[1]` 语义内联（×1/256 + 1/32 半纹素中心，禁提前除） |
| `gl_MultiTexCoord2` | 保留输入（mc_midTexCoord 相关由 prologue 提供） |
| `gl_TextureMatrix[0/1]` | 常量矩阵内联 |
| `gl_ModelViewMatrix / gl_ProjectionMatrix / gl_ModelViewProjectionMatrix / gl_NormalMatrix` | uniform heap 成员 / push constant MVP（per-draw） |
| `ftransform()` | `(u_mvp * a_pos)` |
| `texture2D/3D` | `texture()/texelFetch()`（编译期区分 LOD 语义） |
| `shadow2D*` | `sampler2DShadow` + `texture(samp,vec3)`（compare_op 恒定 LEQUAL） |
| `gl_FragData[n] / gl_FragColor` | `layout(location=n) out` 块 |
| `gl_FogFragCoord / gl_Fog` 系 | heap 成员展开 |

### 3.3 Prologue 注入（vertex）
mc_Entity 解码（id+1<<1|fluid 位）、at_midBlock、midTexCoord、切线框架（角度对解码法线基构建，两侧同基——Vitrail 教训）。
### 3.4 编译链
shaderc(target-env=vulkan1.3) → SPIR-V → spvc 反射 ↔ 绑定表交叉校验（不一致=编译期错误）→ 可选 spirv-opt → 异步管线建立（worker pool）+ `VkPipelineCache` 落盘（键: driverID+源哈希+选项哈希）。翻译产物缓存目录可导入导出（只分发译文）。

## 4. 包语义实现表

### 4.1 Program 族 → 渲染路径
`shadow/shadow_solid/cutout/water` → S3 阴影层（静态摊销+动态小图）；`gbuffers_*` 各族 → §2 路由；`prepare1..n` → PerFrame；`deferred1..31/composite1..99/final` → ScreenChain（融合候选）；`begin/setup` → 帧首一次性。

### 4.2 缓冲语义（colortex0–15 + shadowtex/shadowcolor0–1 + depthtex0–2）
- 记录: 格式 token 全集（R8/RG8/RGBA8/RGBA16/RGBA16F/R11F_G11F_B10F/RGBA32F/R8UI…）、flip 布尔、clear 色、clearEnabled。
- 别名: 同尺寸+同纪元存活不相交者入 VMA aliasing 组；LAZILY_ALLOCATED 无桌面堆（RADV），一律 aliasing。
- depthtex0/1/2 = 主深/半透明前拷贝/无水深 —— 在 gbuffer 尾以 D32 拷贝生成（copy 不跨格式，全 D32 一致）。

### 4.3 Sampler 绑定表（确定性生成，shaders.txt L256–424 锚定）
`tex=gtexture=0, lightmap=1… normals/specular 联动, depthtex0=6, shadowtex0=4, shadowtex1=5, shadowcolor0=7…, colortexN=8+N(colortex0..7), noisetex=15, colortex8..15=16..23`。反射名↔槽不符即编译错误。

### 4.4 Uniform 分类（~80 个，shaders.txt L170–249）
| 类别 | 通道 | 例 |
|---|---|---|
| 每-draw | push constant（mat4 mvp, vec3 chunkOffset, baseId…） | 模型矩阵 |
| 每-pass | heap 段（动态偏移绑定一次） | shadowModelView/Inverse |
| 每帧 | heap 帧头 | cameraPosition, up/太阳月亮相量, screenSize/frameTime/fog 系, eyeBrightness… |
| 事件更新 | heap + 脏标记 | worldTime（每帧写但常量段）, rainStrength 平滑 |
| 计算 derived | CPU 启动/低频 | sunPathRotation, eyeHeightSmooth…

Heap: 单 UBO，帧头+pass 段线性排布，`AlignedStruct` 对齐工具；每 in-flight 槽副本。

## 5. 顶点格式字节布局（并集驱动，24–40B）

| 字段 | 字节 | 存在条件 |
|---|---|---|
| vanilla 四元素原偏移 | 20 | 恒在（不动） |
| mc_Entity(u32: (id+1)<<1\|fluid) | +4 | 任一程序引用 |
| midTexCoord(f32×2) | +8 | 同上判定 |
| at_midBlock(u8×3+pad) | +4 | 同上 |
| normal+tangent 打包(u32: octX11|octY11|handed1|angle8) | +4 | 任一引用 normal/tangent |
| separateAo 第二色(u8×4) | +4 | `separateAo=true` |
合计 20→40，4B 对齐；追加字段恒置尾（vanilla 着色器暖机兼容不变式）。

## 6. 执行模型（v4 SSCA 完整状态机）

### 6.1 线程与池
- 录制线程 T=min(物理核−2,4)，绑核平台线程 `Vertex-R{i}`；协程仅 loader IO。
- 池: L=2 帧 in flight → `2×T+N` command pools；每槽独立 heap/CB 副本，fence 空闲后复用。
- 提交: 主线程单次 `vkQueueSubmit`(graphics)；transfer 队列独立提交网格上传；HUD 由游戏后续自然衔接（同队列顺序+我们尾屏障）。

### 6.2 节点分类与状态机
```
节点类别: Amortized(地形不透明/静态阴影) | PerFrame(动态/半透明/post)
纪元(epoch) 触发源: 可见集变化│重网格完成│包切换│选项变更│分辨率变更
Amortized CB 生命周期:
  VALID(slot k) --脏事件--> STALE(k) --> 下次该槽使用前重录 --> VALID
  全局失效(包/选项): 所有槽 STALE + heap 纪元号++
不变式: 烘焙进 Amortized CB 的绑定/偏移/索引全部纪元稳定（push constant 禁用）
```
### 6.3 同步对象表
| 对象 | 数量 | 用途 |
|---|---|---|
| renderFinished semaphore | L | submit→present 衔接（游戏侧消费）|
| transferDone timeline | 1 | 网格上传→地形绘制依赖（wait 值推进）|
| per-slot fence | L | 槽回收 |
| barrier 分组 | 每帧一次 `vkCmdPipelineBarrier2` 合批（stage 掩码收紧；禁 read-to-read）|

### 6.4 Hi-Z（G3 实验）
上帧主深 mipmap 金字塔（blit 链，O4 友好）→ compute 按 section 簇(8³) 保守剔除 → multiDrawIndirectCount。结果变化=可见集脏事件（天然互锁）。

## 7. S3 阴影缓存算法

```
sunQuant = quantize(sunDir, Δθ=0.25°)
静态层: valid if sunQuant 未变 且 dirtySections∅
  失效: 受影响 tile 局部重绘(section→tile 映射); 太阳步进=全失效(罕见,步长足够大)
动态层: 实体/叶子每帧画入小图, 采样期与静态图合成(shadowtex0=合成图)
compare_op 恒 LEQUAL, 任何情况不得翻转(Z-cull 保护)
```

## 8. 测试与验收（testkit）

- **回放**: 固定种子+脚本相机(JSON 时间线) → 逐帧图像哈希(xxh3)+关键帧 PNG。用途: 回归基线/陈旧读取捕获/驱动升级对比。
- **差分**: 与 Iris 同设置截图 SSIM+逐像素容差双阈值；语料=BSL/Complementary Reimagined/Bliss/SEUS PTGI/Rethinking Voxels/Photonics。
- **性能协议**: 锁 GPU 时钟；场景三件套(平原飞行/山区洞穴/水下夜景)；p50/p99 帧时间 + SSCA 两路径占比计数器；对手=OptiFine-GL/Iris+Sodium/Vitrail。
- **CI**: debug 开 validation+best-practices 层；release 关。注入失败路径单测=Tier 0 自动降级行为断言。

## 9. 风险登记（合并终版）

| # | 风险 | 缓解 |
|---|---|---|
| R1 | 设备创建注入点随版本断裂 | 单点 mixin+启动自检；失败→Tier 0 降级 |
| R2 | Sodium arena 步长耦合 | 版本锁+构造器 mixin（Vitrail 0.9.2 先例）|
| R3 | SSCA 陈旧读取 | 回放哈希逐帧校验；占比计数器异常自动退全录 |
| R4 | 翻译正确性长尾 | 语料库+compat-log.md 响亮失败目录 |
| R5 | 许可 | 仅 Iris/Vitrail(LGPL)；Sulkan(GPL) 零接触；缓存只发译文 |
| R6 | 上游配额/网络不稳 | 文档先行离线可建（本次已实证）|

## 10. 路线图（门径+验收）

| 门 | 内容 | 验收 |
|---|---|---|
| **G0** | core 注入+同设备三角形直写主目标 | RADV 0 validation error；注入失败自动 Tier 0 断言通过 |
| **G1** | frontend(min)/translate(min)/framegraph(线性)/geometry(不透明)/exec(单线程) 极简包闭环 | 回放哈希稳定；与 Iris 差分过阈值 |
| **G2** | 兼容冲刺+逐族协商落地+SSCA 最小形态(仅不透明地形) | Top10 语料 compat-log 完备；回放零漂移 |
| **G3** | 多线程录制+bindless 收紧+S3+异步 bloom(O3)+Hi-Z 实验 | 1080p RD12 p50 ≥3× OptiFine-GL；p99 改善；占比计数器健康 |
| **G4** | O1 subpassLoad 改写器+O2 内部分辨率缩放 | O1 边界消除计数>0 且差分通过；O2 三档缩放可用 |

## 11. 开放问题（诚实清单）
1. Blaze3D 设备创建代码的具体形态（26.2 正式版未读源）→ G0 第一天确认。
2. updateAfterBind 若游戏已开但上限吃紧 → 退 per-frame set 方案（接口不变）。
3. MoltenVK 上特性注入成功率 → macOS 支持延后独立评估。
4. DH LOD 几何接入 Tier 2 的可行性 → 待 DH Vulkan 化进展。

---

*证据链见 docs/ 其余 13 份文件；本文与其冲突时以本文为准，除非证据文件被新测量推翻。*

---

## 12. 工程完备性层（终稿补遗 · 范式已收敛后的五块拼图）

> 再证伪一轮的结论：架构无新范式可挖——这本身是结论。以下五项决定方案能否在现实中存活。

### 12.1 Iris 即可执行规范（持续差分 CI）
把 §8 的差分从"发布前手动"升格为**持续自动 oracle**：CI 内 headless 实例（Xvfb/surfaceless EGL），同一 RADV 设备上并行跑 Iris-GL 与 Vertex-VK，固定种子相机矩阵逐帧比对。每次翻译器/帧图改动即刻对全语料验证，回归在合并前暴露。Iris 从参考实现升格为**可执行的规范本体**。

### 12.2 Device-lost 遏制与自动降档
旧包是不可信输入：越界数组索引等可致 device lost，杀死整个游戏会话。对策链：
- `VK_EXT_device_fault` 捕获故障转储（本地文件，无遥测上传）；
- 包级隔离标记：故障归因到最近提交的包管线 → 自动切该包 Tier 0 并提示重载；
- 会话不崩：世界渲染即时回退安全路径。

### 12.3 显存预算总督
加载期预估池需求（格式×分辨率×aliasing 后净额），超阈值时按类降级（阴影图分辨率减半→非关键 colortex 封顶 2×屏），全程用户可见且可关。防 RGBA32F 栈 @4K 的爆显存惨案。

### 12.4 版本矩阵与发布自动化
Sodium/NeoForge/Fabric/MC 快照版本组合爆炸 → CI 构建矩阵 + 安装期自检（不满足即 Tier 0 + 明示原因）。启动全并行：网格重建∥翻译∥管线编译重叠于加载屏，进度可见。

### 12.5 性能回归门
锁时钟基准作业进 CI：p50 相对存储基线退化 >3% 即拒 PR。没有这道门，G3 的性能资产会在迭代中静默流失。

### 对门径的映射
| 项 | 落位 |
|---|---|
| 12.1 | G2 起 CI 常驻 |
| 12.2 | G2（随逐族协商一起落地）|
| 12.3 | G3 |
| 12.4 | G0 起渐进 |
| 12.5 | G3 门径本身的一部分 |
