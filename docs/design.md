# Vertex 详细设计（依据 docs/ 全部调研文档）

> 上游: `design-audit.md`（定稿结论）。本文将其落为可实现的工程蓝图。实现语言 **Kotlin**，目标平台 Fabric + MC 26.2 Vulkan 后端，RADV/RDNA2 首位。
> **⚠️ 拓扑层已被 `design-v3.md` 取代**：双设备+DMA-BUF → 协同驻留单设备（劫持设备创建点注入特性，零拷贝直写主目标）。本文其余模块接口/翻译规则/门径仍有效，按 v3 修订表执行。

---

## 0. 进程拓扑与帧契约

```
┌─ Minecraft 进程 ───────────────────────────────────────────────┐
│ Blaze3D(Vulkan)                    Vertex 自持 VkDevice        │
│ ├─ 世界渲染被 mixin 短路            ├─ M4 几何桥(Sodium tap)   │
│ ├─ 主目标纹理 = 导入容器            ├─ M3 帧图 → M5 执行器     │
│ └─ HUD/UI 正常合成(采样主目标)  ←── │ M6 DMA-BUF 导出最终色    │
└────────────────────────────────────────────────────────────────┘
```

- 挂接点：世界渲染结束、无打开 render pass 处（`minecraft-ecosystem.md`：最廉价挂接点；深度在几行后清除，需在此之前取副本）。
- 帧契约：Vertex 每帧向游戏主目标纹理提交一张最终 LDR 图像；游戏侧零改动消费。
- 坐标：全程 OpenGL 方向约定，仅 swapchain blit 翻转——包无需 Y 翻转（`minecraft-ecosystem.md`）。

---

## 1. 包结构（Gradle 多模块，Kotlin DSL）

```
vertex/
├─ build.gradle.kts            # fabric-loom + LWJGL 3.4.2 BOM(kool 同款)
├─ core/          M0 设备核心
├─ frontend/      M1 包前端(Iris LGPL 移植)
├─ translate/     M2 翻译器
├─ framegraph/    M3 帧图编译器
├─ geometry/      M4 几何桥
├─ exec/          M5 执行器
├─ handoff/       M6 交接缝
├─ shadow/        S3 阴影缓存
└─ testkit/       语料库差分 harness
```

依赖方向单向：`exec → {framegraph, geometry, shadow, core}`；`framegraph → {frontend, core}`；无环。

---

## 2. 模块接口（Kotlin，小接口大实现）

### M0 core
```kotlin
class GpuContext private constructor(...) : AutoCloseable {
    val graphics: Queue; val transfer: Queue; val compute: Queue?
    fun image(spec: ImageSpec): GpuImage          // VMA 子分配; RT 自动 dedicated(NVIDIA 建议)
    fun buffer(spec: BufferSpec): GpuBuffer
    fun allocator(): VmaAllocator                 // aliasing 组用 custom pool
    companion object { fun create(lwjglSurface: PhysicalDeviceInfo, opts: Features): GpuContext }
}
data class Features(                            // VK_API 1.3 起步(vulkanmod-internals.md 清单)
    val dynamicRendering: Boolean = true, val sync2: Boolean = true,
    val updateAfterBind: Boolean = true, val multiDrawIndirect: Boolean = true,
    val timelineSemaphore: Boolean = true,
)
```
- 内存纪律（`kotlin-jvm.md`）：热路径零堆分配。结构体参数走 `MemoryStack.framePush()`；跨帧长命对象预分配；`Arena.ofConfined` 用于单 pass 编排 scratch。
- 并发纪律：**提交路径禁止虚拟线程/协程**（FFM 下调 pin 实证）；固定绑核平台线程。

### M1 frontend
```kotlin
class PackFrontend {
    fun load(root: Path): PackModel             // 解析失败=响亮异常,带文件:行号
}
class PackModel(
    val programs: Map<ProgramKey, ProgramSource>,   // ProgramKey=(family,name)
    val buffers: BufferTable,                       // colortexN 格式/flip/clear(shaders.txt 锚定行)
    val bindings: SamplerBindingTable,              // depthtex0=6 ... colortex8..15=16..23(pack-format-spec.md)
    val options: OptionTree,
    val customTextures: List<PackTexture>,
    val needsSeparateAo: Boolean,                   // 决定顶点格式并集(terrain.md 规则)
)
```
- 双轨语义：Iris 行为为准，缺口回退 OptiFine shaders.txt；未定义行为进 `compat-log.md`（响亮失败目录）。

### M2 translate
```kotlin
object Translator {
    // 返回可编译的 Vulkan-GLSL 与描述符计划; 不做任何运行时翻译
    fun translate(src: ProgramSource, pack: PackModel, fmt: VertexLayout): TranslatedProgram
}
class TranslatedProgram(
    val vulkanGlsl: PerStage<String>,
    val descriptorPlan: DescriptorPlan,         // 由 SamplerBindingTable 确定性生成, spvc 反射交叉校验
    val usesShadowCompare: Boolean,
    val sourceHash: Long,                       // 管线缓存键成分(身份随源变,game-graphics-api 教训)
)
```
重写规则表（继承 `minecraft-ecosystem.md` Vitrail 已验证集）：
| 旧构造 | 重写 |
|---|---|
| `attribute/varying/uniform sampler2D` | 显式 in/out/layout 绑定 |
| `gl_Vertex/gl_Color/gl_MultiTexCoord{0,1,2}/gl_Normal` | 顶点输入（含 mc_* 打包字段解码注入 prologue） |
| `ftransform()/gl_ModelViewMatrix…` | uniform heap 成员 + push constant MVP |
| `texture2D/texture3D/shadow2D` | `texture()/texelFetch()/sampler2DShadow(compare)` |
| `gl_FragData[n]` | location 化 out 块 |
| 光照坐标 | 保持 `gl_TextureMatrix[1]`(1/256 缩放+1/32 半纹素) 语义（terrain.md 明令） |

编译：shaderc(target-env=vulkan1.3) → SPIR-V →（可选 spirv-opt 尺寸清理）→ spvc 反射校验绑定一致性 → 异步建管线 + `VkPipelineCache` 落盘（键=驱动版本+源哈希+选项哈希）。加载期 worker pool 全量完成，**消灭每帧一管的暖机场**。

### M3 framegraph
```kotlin
fun compile(pack: PackModel, caps: DeviceCaps): CompiledFrameGraph
class CompiledFrameGraph(
    val nodes: List<FrameNode>,        // Shadow | Geometry(family) | ScreenChain(fused) | Copy | Handoff
    val edges: List<Edge>,             // 仅真依赖边; stage 掩码收紧到 fragment/compute
    val aliasGroups: List<List<ResourceId>>,  // VMA aliasing 的瞬态 colortex 组
)
```
融合判据（保守，桌面修正见 `vulkan-subpasses.md`）：同分辨率连续全屏 pass，输出唯一消费者为下一节点，flip 环检测通过 → 合并为单 renderpass 多 subpass 或紧掩码屏障序列；否则保持独立。瞬态资源 `STORE_OP_DONT_CARE`/`LOAD_OP_CLEAR|DONT_CARE` 按 flip 表生成。

### M4 geometry
- Sodium tap 五点（terrain.md 已验证清单）：格式访问器替换（仅在 chunk renderer 重建瞬间生效）、per-pass 管线短路、开 pass 附加颜色目标、绑定注入、方块/流体写入点 + 排序器顶点拷贝。
- 顶点布局：原 4 元素偏移不动，追加字置尾（vanilla 着色器继续可用）；并集决定 24–40B。
- 上传：重建事件才拷贝；稳态零拷贝。动态几何每帧捕获经 transfer 队列环形上传。
- 阴影（S3）：静态层按太阳方向量化角步失效 + section 脏集增量；动态层小图逐帧合成；compare_op 全程恒定（Z-cull 保护）。

### M5 exec
```kotlin
class FrameExecutor(threads: Int) {           // threads = 物理核-2, 绑核平台线程
    fun execute(frame: FrameCtx, graph: CompiledFrameGraph, res: FrameResources): SubmitBatch
}
```
- 录制粒度=管线桶 secondary cmd buffer（禁微型 buffer，NVIDIA Don'ts）；池布局 **L×T+N**（L=2 帧 in flight）。
- 提交：批量化单次 `vkQueueSubmit`；帧首提前录下一帧规避批量延迟；present 队列可选 compute 族（vkBasalt 案例）。
- 绑定：update-after-bind 大采样器数组 + uniform heap 单 UBO（对齐打包）+ 每-draw push constant；绘制列表按 `(pipelineId)` 排序零切换。
- 异步 compute 位：bloom 链跨帧重叠实验特性开关（默认关，A5 条件验证后启用）。

### M6 handoff
```
v1(Linux):  最终色 VkImage --vkGetMemoryFdKHR(DMA-BUF)+semaphore fd--> 游戏纹理导入
退路 b:     device-local→host→device 拷贝（多一帧延迟+2×末帧带宽）
退路 c:     仅接管 post 链(Vitrail 等效架构, 保留大部分屏障红利)
```
- macOS/MoltenVK 无 external memory → 明确不支持（audit A6）。

---

## 3. 关键时序（一帧）

```
[主线程]   acquire→记录 primary(skeleton+post 链)→submit→handoff 导出
[T1..Tn]   阴影桶 ∥ gbuffer 桶(secondary)          ← L×T+N 池
[Xfer]     动态几何上传(staging 环)
[GPU]      阴影(缓存命中则跳过)→gbuffer→融合 post 链→handoff blit
```

---

## 4. 测试与验收（对应 audit 门径）

| 层 | 手段 |
|---|---|
| M2 正确性 | 固定种子+锁定相机路径回放；与 Iris 截图差分（容差阈值）；语料=BSL/Complementary Reimagined/Bliss/SEUS/Rethinking Voxels |
| M3/M5 性能 | MangoHud+锁 GPU 时钟；p50/p99 帧时间；基线=OptiFine-GL / Iris+Sodium / Vitrail |
| 全局 | CI 开 validation layers（性能测量必关）；compat-log.md 响亮失败目录随语料增长 |

验收线（不变）：1080p RD12 ≥3× OptiFine-GL p50；G0=自有设备三角形成功导入且 0 validation error。

---

## 5. 实施顺序（风险递减）

1. **G0**: `core`+`handoff` 尖峰 —— 三角形→DMA-BUF→游戏可见。
2. **G1**: `frontend(min)`+`translate(min)`+`framegraph(线性)`+`geometry(不透明)`+`exec(单线程!)` —— 先正确后并行（NVIDIA：先跑通再优化）。
3. **G2**: 兼容冲刺 + compat-log。
4. **G3**: 多线程录制、bindless 收紧、阴影缓存、异步 compute 实验、基准报告。

每步只引入一个变量；G1 单线程是故意的——把正确性与并发解耦。
