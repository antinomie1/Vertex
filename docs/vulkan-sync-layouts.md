# Vulkan 同步 / 屏障 / Layout 事实（含 NVIDIA 官方清单）

## NVIDIA《Vulkan Dos and Don'ts》(2019-06, 更新 2025-01) —— 全文要点
来源: https://developer.nvidia.com/blog/vulkan-dos-donts/

### 提交与录制
- **驱动不会把你的 API 命令移到 worker 线程**——多线程录制收益直接来自并行核利用。
- 每次 `vkQueueSubmit()` CPU 开销显著 → 批量化；但激进批处理引入延迟，需权衡提前提交。
- **不要录只含少量小 draw/dispatch 的微型 command buffer**：驱动会插入额外 GPU 工作（状态复位+优化）；且**一个 command buffer 的 compute 无法与后续 command buffer 的 compute 重叠（即使无屏障）**。
- `vkAllocateCommandBuffers/Begin/End` 必须在填充线程调用（有可测 CPU 成本）。
- Command pool 复用公式: **L×T+N**（L=缓冲帧数，T=录制线程数，N=secondary 余量）。

### 管线
- 异步创建 + pipeline cache；specialization constants 可减指令/寄存器并替代离线排列。
- **每次 `vkCmdBindPipeline` 都有显著 CPU 和 GPU 成本** → 按 shader 分组 draw、少动态状态。
- 深度比较函数翻转（less↔greater）**会打掉 Z-cull** → 阴影比较采样器的 compare_op 保持稳定一致！
- 切换 tessellation/geometry/task/mesh 有无是昂贵操作；don't-care 字段用一致的合理默认值以最大化管线复用。

### 屏障（对 Vertex M3 最关键）
- **"冗余屏障与伴随的 wait-for-idle 是现代 API 移植的主要性能问题"**；barrier 可能引发 GPU pipeline flush。
- 优先 buffer/image barrier 而非全局 memory barrier（除非合并很多个）。
- 用 sync2；**把多个 barrier 合进一次 `vkCmdPipelineBarrier2()`——硬件取最坏情况而非顺序执行全部**。
- src/dstStageMask 收紧到实际阶段（fragment-only/compute-only）。
- 内容不需要时用 UNDEFINED 初始 layout；`vkCmdSetEvent2/WaaitEvents2` 可做异步屏障不阻塞执行。
- usage flags 取最小集，冗余 flag 触发多余 flush/stall；消灭 read-to-read 屏障。

### 内存
- 子分配（vkAllocateMemory 昂贵）；VK_EXT_memory_budget 防 demotion 卡顿；Linux 无自动 VRAM paging 兜底。
- VK_EXT_pageable_device_local_memory 设优先级防关键资源被逐出。
- **专用分配（dedicated allocation）对 color/depth attachment 有性能收益，pre-Turing 尤甚**。
- 始终 OPTIMAL tiling；深度格式偏好 D24S8/D32（D32S8 非最优）；CONCURRENT sharing 对驱动无额外开销。

### 调试
- 锁 GPU 时钟测量；验证层开着别测性能；CPU 受限时测 GPU 无意义。

## 与 Blaze3D 对照的结论
Mojang 后端"每 pass 一次全内存屏障 + GENERAL layout 终身"恰好命中 NVIDIA 清单的多条 Don't（全局屏障、不分组、layout 不管理）。Vertex 的 M3（融合+分组+紧掩码+optimal layout）即按此清单反向施工。

## 补充事实
- RasterGrid 2026-03 屏障/layout 长文（业界误解澄清）。https://www.rastergrid.com/blog/gpu-tech/2026/03/vulkan-memory-barriers-and-image-layouts-explained
- Granite 作者 render-graph 深潜系列（2017-08-15 起）: transient attachment 别名/生命周期是帧图核心价值。https://themaister.net/blog/2017/08/15/render-graphs-and-vulkan-a-deep-dive/
- Khronos 官方 layout transition 成本样例。https://docs.vulkan.org/samples/latest/samples/performance/layout_transitions/README.html
- VK_EXT_load_store_op_none refpage（省 store 带宽的合法工具）。https://registry.khronos.org/vulkan/specs/latest/man/html/VK_EXT_load_store_op_none.html
