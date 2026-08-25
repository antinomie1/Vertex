# Subpasses / 融合 —— 硬数据与桌面修正

## Khronos Vulkan-Samples「Subpasses」样例（Arm 维护）
来源: https://raw.githubusercontent.com/KhronosGroup/Vulkan-Samples/main/samples/performance/subpasses/README.adoc

- Mali-G76 实测：G-buffer+光照合并为单 render pass 双 subpass 后，物理 tile 数 262.2k/s vs 614.7k/s（**~55% 带宽削减**）。
- 合并条件（Arm）: 能省 write-out/read-back；唯一 attachment 数 ≤8；depth/stencil 不变；采样数一致；**tile buffer 颜色预算 ≤128-bit/像素**（Mali-G72 起 256-bit）。超预算 → 不合并，PTILES 近乎翻倍。
- 瞬态附件: `TRANSIENT` usage + `LAZILY_ALLOCATED` 内存 → 甚至无需真实分配；不设则 fragment job 翻倍（56/s→113/s）。
- 最佳实践: LOAD_OP_CLEAR/DONT_CARE 加载、瞬态 STORE_OP_DONT_CARE、几何后 depth 进 DEPTH_STENCIL_READ_ONLY layout。

## ⚠️ 桌面独显（RDNA2）诚实修正
- Tile-retention 是移动端 GPU 特性。RX 6800 XT L2=4MB，而 1440p RGBA16F colortex≈29.5MB——**桌面不可能把 G-buffer 留在片上**，"55%"不可移植。
- 桌面上融合的真实收益来源：
  1. 消除每 pass 的提交边界与全屏障（Blaze3D 模式下是全 GPU 串行化；自有设备下退化为紧掩码 sync2 屏障）；
  2. 减少 pass 设置开销与 layout 往返；
  3. STORE_OP_DONT_CARE / loadOp 控制省写回；
  4. 背靠背 pass 间的部分 L2 时间局部性（小分辨率或小尺寸目标时才显著）。
- **修订 Vertex 性能模型**: composite 链 GPU 侧收益从此前乐观的"10–25%"下调为"屏障串行化消除为主、带宽为辅"；量化值留待 G3 基准。CPU 侧收益（3–10× vs OptiFine-GL）不受此修正影响。

## 工程要点（采纳）
- 瞬态 colortex 用 LAZILY_ALLOCATED 不可能（桌面离散显存无 lazily 堆）→ 改用 VMA aliasing 达成同等"少占显存"效果。
- 包的 flip 语义要求跨 composite 读上一 pass 全屏输出 → 只有真依赖边插屏障，融合组内用 input attachment（subpassLoad 语义需在翻译层把 texture() 调用改写为 subpassLoad，仅当采样坐标恰为 gl_FragCoord 对应 texel 时可安全替换——保守起见 v1 只做屏障分组不做 subpassLoad 改写）。
