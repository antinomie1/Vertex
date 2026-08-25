# Vulkan 异步计算 / 队列重叠事实

## 官方指导（NVIDIA）
- Advanced API Performance: Async Compute and Overlap（2021-10）: 给出重叠模式清单，明确**跨帧重叠**——上一帧末尾的后处理与下一帧开头的 G-buffer 填充并行——"可带来可观性能收益，前提是应用能足够早地调用 Present()"。
  来源: https://developer.nvidia.com/blog/advanced-api-performance-async-compute-and-overlap

## 原理边界（Interplay of Light, Kostas Anagnostou, 2025-05）
- 计算队列**只有着色执行单元（SM/CU+缓存）**，没有几何处理、光栅化、后端写回单元 → 异步计算只在"图形阶段卡固定功能单元、而 ALU/带宽有余"的窗口里兑现收益；帧内各阶段单元利用率天然不均（阴影/gbuffer 卡 World Pipe+VRAM，后段卡像素处理）——这正是重叠空间。
  来源: https://interplayoflight.wordpress.com/2025/05/27/async-compute-all-the-things
- 该文即一次实测型研究：玩具渲染器的 GPU trace 显示利用率极不均匀。

## 案例研究
- DOOM Eternal Graphics Study: 部分 GPU 粒子模拟跑在 compute 上（带深度缓冲依赖做碰撞）。
  来源: https://www.simoncoenen.com/blog/programming/graphics/DoomEternalStudy
- RDNA vs Turing 实测视频: DOOM Eternal 在 5700XT/RDNA 上异步相关设置带来两位数提升（11%~45% 区间随卡与目标帧率波动）。
  来源: https://www.youtube.com/watch?v=AByMt76hjFM
- GCN 硬件计算队列可直接承担 present（DOOM 在特定 AA 设置下使用）；Linux 上 vkBasalt issue 讨论其输入延迟影响 → **present 队列选择本身也是优化点**。
  来源: https://github.com/DadSchoorse/vkBasalt/issues/34

## 对 Vertex 的映射（初稿，待 SyncFacts2/PushDesc 回报后修订）
- bloom/blur 子链丢 async compute 与下一帧阴影 pass 重叠 = NVIDIA 推荐模式的标准应用。
- 跨帧重叠要求提交路径足够早释放 → 支持多线程录制设计（CpuBindless 分片补数据）。
