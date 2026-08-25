# Vertex 设计审计报告（v2，2026-08-25）

> 依据: docs/ 下全部调研文件。语言: **Kotlin (JVM) + LWJGL3**。硬件基线: RX 6800 XT / RADV / CachyOS。

## 一、审计结论（相对 v1 的修订）

| # | v1 断言 | 审计结果 | 修订 |
|---|---|---|---|
| A1 | subpass 融合省 10–25% 带宽 | **高估**。tile-retention 是移动端特性；Mali-G76 的 55% 不可移植到 RDNA2（L2=4MB ≪ 单张 colortex） | 收益重述为"消除屏障串行化 + pass 设置开销 + load/storeOp 控制"；量化留待 G3 |
| A2 | Vitrail 慢因 = 每 pass 全屏障 | **确认并强化**。issue #161 实测 GPU 大量空闲、CPU→GPU 延迟 6.74 vs 2.26ms → CPU/提交路径受限 | 自有设备路线的收益主要兑现在 CPU 侧，与 A1 修正自洽 |
| A3 | bindless 可行 | **确认**。NVIDIA 官方推荐无界数组描述符表；VK 1.2 核心；RADV 上限运行时查询 | 维持 v1 决策 |
| A4 | 多线程录制有收益 | **确认但加边界**。驱动不会替你并行 API 调用（NVIDIA）；微型 cmd buffer 反而有害；compute 不跨 cmd buffer 重叠 | 录制粒度按"管线桶"聚合，禁止微型 buffer |
| A5 | async compute 有肉 | **条件成立**。计算队列只有 ALU/缓存单元；NVIDIA 推荐"上帧后处理×下帧 gbuffer"跨帧重叠模式；pre-Ampere 勿与图形队列 compute 混排 | bloom 链异步化列为 G3 实验，非主线承诺 |
| A6 | DMA-BUF 交接可行 | **确认**。VK_EXT_external_memory_dma_buf+fd 在 RADV/Mesa 完整实现，PrimusVK 为双 device 先例 | M6 维持；Windows KMT 后置 |
| A7 | 工具链 JVM 内闭环 | **确认**。VulkanMod 生产使用 lwjgl-vma/shaderc/spvc 3.3.3；kool 为 Kotlin 先例 | 版本直接采用 LWJGL 3.4.2（kool 同款） |
| A8 | Kotlin 并发模型 | **修正**。虚拟线程在 FFM/JNI 下调时仍 pin → 禁用于提交路径 | 固定绑核平台线程录制；协程仅编排/IO |
| A9 | 阴影每帧全量重画 | **可优化**。UE5 VSM 页缓存/逐图元失效、静态几何缓存是成熟实践 | 新增阴影缓存子系统（见 S3） |

## 二、定稿架构（模块与接口）

```
Vertex/
├─ core/        M0 设备核心
├─ frontend/    M1 包前端（Iris LGPL 移植）
├─ translate/   M2 GLSL→SPIR-V 翻译器
├─ framegraph/  M3 帧图编译器
├─ geometry/    M4 几何桥（Sodium tap）
├─ exec/        M5 执行器（录制/提交/bindless）
└─ handoff/     M6 交接缝（DMA-BUF）
```

每个模块 = 深模块：小接口、大实现。模块间只经接口通信；测试面即接口面。

### M0 core — VkDevice 属于我们
- 复用游戏物理设备（LWJGL glfw surface），自建 instance/device：VK_API 1.3（dynamic rendering + sync2 入核），特性集参考 VulkanMod 实证清单 + timelineSemaphore + descriptorIndexing(updateAfterBind) + multiDrawIndirect。
- 队列族：graphics（主提交）、dedicated transfer（网格上传）、compute（异步实验位）、present 选择可调（vkBasalt 案例）。
- 内存：VMA 子分配 + dedicated allocation 给 RT（NVIDIA：对 attachment 显著）；VK_EXT_memory_budget 监控。
- Kotlin 纪律：热路径零堆分配——结构体进 MemoryStack（帧 scratch）/ Arena.ofConfined（作用域生命周期）；GC 只碰冷路径。

### M1 frontend — 兼容性即规范
- 从 Iris 移植解析/语义（LGPL 合规，Vitrail NOTICE 已开先例）；OptiFine 格式为规范本体（十年语料+明确验收）。
- 输出：程序集合、buffer flip/格式表、设置树、自定义纹理、顶点属性需求并集。

### M2 translate — 编译期一次性
- 规则继承 Vitrail 验证集（旧内建→显式输入/UBO/push constant；prologue 注入 mc_* 解码；gl_TextureMatrix[1] 光照坐标约定保持）。
- shaderc(target-env vulkan1.3) → SPIR-V → spvc 反射 → 描述符槽计划；可选 spirv-opt 尺寸清理。
- 全部管线在加载期 worker pool 编译 + `VkPipelineCache` 落盘（消灭 Vitrail 式每帧一管的暖机场）。
- specialization constants 用于包选项（NVIDIA：减指令/寄存器）。

### M3 framegraph — 按 NVIDIA 清单反向施工
- DAG 解析包 pass 序列；**融合判据保守化**（桌面无 tile retention）：以"屏障组最小化"为目标——同尺寸连续全屏 pass 合并为单 render pass 多 subpass 或紧掩码屏障序列，取驱动合并者胜。
- 屏障规则（逐条对应 NVIDIA Don'ts）：合批进单次 vkCmdPipelineBarrier2；stage 掩码收紧；禁 read-to-read；内容弃用即 UNDEFINED；STORE_OP_DONT_CARE 用于被覆写中间体；loadOp=CLEAR/DONT_CARE。
- transient colortex VMA aliasing（LAZILY_ALLOCATED 无桌面堆，aliasing 等效）；RT dedicated allocation。
- layout 全程 OPTIMAL + DCC 友好用法（GPUOpen：压缩自动生效，忌触发解压的用法）。

### M4 geometry — Sodium tap（Vitrail 路线已验证）
- 重建时拷贝 section mesh（格式并集加宽，24–40B/顶点）；动态几何每帧捕获上传走专用 transfer 队列。
- 参考 VulkanMod 的 AreaBuffer/Area 子分配与 UploadManager 结构。

### S3 shadow cache（新增，源自审计 A9）
- 静态地形阴影图：太阳方向量化步进失效 + section 脏集增量更新（UE VSM 思想的游戏化简化）；
- 动态层（实体/叶子）小图每帧重画合成；
- sampler2DShadow → 硬件 compare_op 采样器，且**全程恒定 compare 方向**（NVIDIA：翻转比较函数打掉 Z-cull）。

### M5 exec — 提交路径（超越 OptiFine 的主战场）
- 绑核平台线程 ×(L×T+N) command pool 布局；secondary cmd buffer 按管线桶聚合录制（杜绝微型 buffer）；
- 批量 submit，帧首提前提交规避批处理延迟；
- bindless 大采样器数组 + uniform heap UBO（AlignedStruct 式打包）+ push constant 每-draw；
- 管线零切换排序（NVIDIA：BindPipeline 双侧昂贵）；
- 异步 compute 位留给 bloom 链跨帧重叠实验（A5 条件成立才启用）。

### M6 handoff — DMA-BUF 导入游戏目标纹理
- Linux v1：external memory fd + external semaphore 同步（PrimusVK 先例）；Windows KMT 后置；MoltenVK 明确不支持 → macOS 出局（诚实边界）。

## 三、性能模型（终版，可证伪）

| 场景 | vs OptiFine-GL | 依据链 |
|---|---|---|
| CPU 受限（1080p/RD 高） | **3–10×**（承诺线 ≥3×） | Vitrail GPU 空闲实证→瓶颈在提交；NVIDIA 并行录制/批量提交/bindless/零切换全部兑现于此 |
| composite 密集（BSL 类） | GPU 侧温和收益（屏障消除+store 控制），幅度实测 | A1 修正：放弃 tile 幻觉 |
| fragment 极限（SEUS@4K） | ≈持平 | 物理极限，诚实声明 |

## 四、风险登记

| 风险 | 缓解 |
|---|---|
| DMA-BUF 导入在特定 compositor/驱动组合失败 | G0 尖峰先行；退路=仅接管 post 链（保留大部分屏障红利）|
| Sodium arena 步长耦合 | 锁版本矩阵 CI（Vitrail 0.9.2 mixin 教训）|
| 翻译正确性长尾 | Top10 包语料库 + 截图差分 harness + 响亮失败目录（Vitrail 方法论）|
| 上游配额/沙箱网络不稳（本次调研实证）| 文档先行、离线可建；CI 不依赖外网搜索 |
| 许可 | 只碰 Iris/Vitrail(LGPL)；Sulkan(GPL) 一行不看 |

## 五、路线图（门径制）

- **G0**（~1 周）：自有 device 三角形 → DMA-BUF 导入 → 游戏内可见。验收：RADV 上 0 validation error。
- **G1**（~3 周）：gbuffers_terrain 不透明 + 极简包 composite/final 闭环。验收：与 Iris 同种子截图差分 <阈值。
- **G2**：Top10 包兼容冲刺 + 失败目录文档化。
- **G3**：性能四连（屏障分组/store 控制/多线程录制/bindless）+ 阴影缓存。验收：1080p RD12 ≥3× OptiFine-GL p50 帧时间； frametime p99 改善。
